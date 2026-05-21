package com.playerbrowser.app.network

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
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

    private val client: OkHttpClient by lazy { build() }

    /**
     * Hosts that previously needed an OkHttp main-frame fetch. Subresource
     * requests to these hosts are also routed through OkHttp so the rest of
     * the page (CSS / JS / images / XHR / iframes) doesn't get blocked by
     * the same ISP DPI that blocked the main frame.
     */
    private val bypassedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun build(): OkHttpClient {
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
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!SniBypassSwitch.enabled) return null
        val method = request.method?.uppercase() ?: "GET"
        if (method != "GET") return null
        val url = request.url ?: return null
        val scheme = url.scheme?.lowercase() ?: return null
        if (scheme != "https") return null
        val host = url.host?.lowercase().orEmpty()

        // Main-frame requests are always intercepted (cheap insurance for any
        // host that turns out to be DPI-blocked). Subresource requests are
        // only intercepted if we've already seen this host need bypass.
        if (!request.isForMainFrame && (host.isBlank() || host !in bypassedHosts)) return null

        val urlString = url.toString()
        val builder = Request.Builder().url(urlString)
        request.requestHeaders?.forEach { (k, v) ->
            if (k.equals("host", ignoreCase = true)) return@forEach
            if (k.equals("connection", ignoreCase = true)) return@forEach
            if (k.equals("cookie", ignoreCase = true)) return@forEach
            if (k.startsWith(":")) return@forEach
            runCatching { builder.header(k, v) }
        }
        builder.header("Accept-Encoding", "identity")

        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        cookieManager?.getCookie(urlString)?.takeIf { it.isNotBlank() }?.let {
            builder.header("Cookie", it)
        }

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body ?: return null
                val bytes = body.bytes()
                val contentType = resp.header("Content-Type")
                val mime = contentType?.substringBefore(';')?.trim().orEmpty().ifBlank { "text/html" }
                val charset = contentType
                    ?.substringAfter("charset=", "")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.ifBlank { null } ?: "UTF-8"

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
                val reason = resp.message.ifBlank { reasonFor(resp.code) }
                if (request.isForMainFrame && host.isNotBlank() && resp.code in 200..399) {
                    bypassedHosts.add(host)
                }
                WebResourceResponse(
                    mime,
                    charset,
                    resp.code,
                    reason,
                    headers,
                    ByteArrayInputStream(bytes)
                )
            }
        } catch (e: IOException) {
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun reasonFor(code: Int): String = when (code) {
        in 200..299 -> "OK"
        in 300..399 -> "Redirect"
        in 400..499 -> "Client Error"
        in 500..599 -> "Server Error"
        else -> "Status"
    }
}
