package com.playerbrowser.app.network

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OkHttp-based fetcher used by the WebView's shouldInterceptRequest hook.
 *
 * Combines:
 *  - Cloudflare DoH (skips ISP DNS)
 *  - FragmentingSocketFactory (splits TLS ClientHello to defeat single-packet
 *    SNI inspection used by some Korean ISPs)
 *
 * Strictly best-effort: any failure returns null so the WebView can fall back
 * to its native loader.
 */
object SniBypassClient {

    // Main-frame client never follows redirects: if OkHttp resolved the
    // redirect internally, the bytes we hand back would belong to a different
    // URL than the one in the WebView's address bar (origin / cookie scope
    // mismatch). The WebView itself handles main-frame 3xx.
    private val mainFrameClient: OkHttpClient by lazy { build(followRedirects = false) }

    // Subresource client DOES follow redirects. The WebView does not follow
    // redirects returned from an intercepted *subresource* response, so a
    // same-host image/CSS/JS that answers with 301/302 (signed CDN URL,
    // http->https, extension rewrite, ...) would silently fail to load if we
    // returned the bare 3xx. Following it here makes those load like they do
    // in the native loader.
    private val subResourceClient: OkHttpClient by lazy { build(followRedirects = true) }

    /**
     * Hosts that previously needed an OkHttp main-frame fetch. Subresource
     * requests to these hosts are also routed through OkHttp so the rest of
     * the page (CSS / JS / images / XHR / iframes) doesn't get blocked by
     * the same ISP DPI that blocked the main frame.
     */
    private val bypassedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun build(followRedirects: Boolean): OkHttpClient {
        val fragmenting = FragmentingSocketFactory()
        // Tiny pool with a 5-second keep-alive. Fresh main-frame loads still
        // benefit from reusing a warm connection for the immediate burst of
        // subresources, but a connection that the middlebox silently RSTs
        // can't sit around long enough to poison the next page navigation.
        val pool = ConnectionPool(4, 5, TimeUnit.SECONDS)
        return OkHttpClient.Builder()
            .dns(DohClient())
            .socketFactory(fragmenting)
            .connectionPool(pool)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!SniBypassSwitch.enabled) return null
        val method = request.method?.uppercase() ?: "GET"
        if (method != "GET" && method != "HEAD") return null
        val url = request.url ?: return null
        val scheme = url.scheme?.lowercase() ?: return null
        if (scheme != "https") return null
        val host = url.host?.lowercase().orEmpty()

        // Main-frame requests are always intercepted (cheap insurance for any
        // host that turns out to be DPI-blocked). Subresource requests are
        // only intercepted if we've already seen this host need bypass.
        if (!request.isForMainFrame && (host.isBlank() || host !in bypassedHosts)) return null

        if (request.isForMainFrame) DebugLog.d("SniBypass", "intercept: $method $host (main frame)")

        val urlString = url.toString()
        val builder = Request.Builder().url(urlString)
        if (method == "HEAD") builder.head()
        request.requestHeaders?.forEach { (k, v) ->
            if (k.equals("host", ignoreCase = true)) return@forEach
            if (k.equals("connection", ignoreCase = true)) return@forEach
            if (k.equals("cookie", ignoreCase = true)) return@forEach
            if (k.equals("accept-encoding", ignoreCase = true)) return@forEach
            if (k.startsWith(":")) return@forEach
            runCatching { builder.header(k, v) }
        }

        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        cookieManager?.getCookie(urlString)?.takeIf { it.isNotBlank() }?.let {
            builder.header("Cookie", it)
        }

        val client = if (request.isForMainFrame) mainFrameClient else subResourceClient
        var resp: Response? = null
        return try {
            resp = client.newCall(builder.build()).execute()
            val contentType = resp.header("Content-Type")
            val mime = contentType?.substringBefore(';')?.trim()?.ifBlank { null }
                ?: "application/octet-stream"
            // Only pass an encoding when the server actually declared one. If we
            // hardcode "UTF-8" here, the WebView trusts it verbatim and never
            // sniffs the document's own <meta charset> — so EUC-KR/CP949 pages
            // (common on Korean sites that omit the HTTP charset) get decoded as
            // UTF-8 and render as ???/mojibake. A null encoding lets the WebView
            // detect the charset from the bytes/meta exactly as it would without
            // our interception.
            val charset = parseCharset(contentType)

            val headers = LinkedHashMap<String, String>()
            resp.headers.forEach { (name, value) ->
                if (name.equals("Content-Encoding", true)) return@forEach
                if (name.equals("Content-Length", true)) return@forEach
                if (name.equals("Transfer-Encoding", true)) return@forEach
                if (name.equals("Set-Cookie", true)) {
                    cookieManager?.setCookie(urlString, value)
                    return@forEach
                }
                headers[name] = value
            }
            if (request.isForMainFrame && host.isNotBlank() && resp.code in 200..399) {
                bypassedHosts.add(host)
            }
            if (request.isForMainFrame) DebugLog.d("SniBypass", "intercept ok: $host -> ${resp.code}")
            val reason = resp.message.ifBlank { reasonFor(resp.code) }
            // Stream the body through to the WebView instead of buffering the
            // entire response in memory. The wrapper closes the OkHttp
            // Response (which releases the socket back to the pool) when the
            // WebView is done reading.
            val body = resp.body
            val stream: InputStream = if (body == null || method == "HEAD") {
                resp.close()
                EMPTY_STREAM
            } else {
                ResponseClosingInputStream(body.byteStream(), resp)
            }
            WebResourceResponse(mime, charset, resp.code, reason, headers, stream)
        } catch (e: IOException) {
            resp?.close()
            DebugLog.w("SniBypass", "intercept io: $host", e)
            null
        } catch (t: Throwable) {
            resp?.close()
            DebugLog.w("SniBypass", "intercept err: $host", t)
            null
        }
    }

    private val EMPTY_STREAM: InputStream = object : InputStream() {
        override fun read(): Int = -1
    }

    private class ResponseClosingInputStream(
        delegate: InputStream,
        private val response: Response
    ) : FilterInputStream(delegate) {
        override fun close() {
            runCatching { super.close() }
            runCatching { response.close() }
        }
    }

    /**
     * Extracts the charset from a Content-Type header, or null if the server
     * didn't declare one. Case-insensitive (some servers send `Charset=`).
     * Returning null is deliberate: it tells the WebView to sniff the encoding
     * itself instead of forcing one.
     */
    private fun parseCharset(contentType: String?): String? {
        if (contentType == null) return null
        for (part in contentType.split(';')) {
            val t = part.trim()
            if (t.startsWith("charset=", ignoreCase = true)) {
                return t.substringAfter('=').trim().trim('"').ifBlank { null }
            }
        }
        return null
    }

    private fun reasonFor(code: Int): String = when (code) {
        in 200..299 -> "OK"
        in 300..399 -> "Redirect"
        in 400..499 -> "Client Error"
        in 500..599 -> "Server Error"
        else -> "Status"
    }
}
