package com.playerbrowser.app.web

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Injects a per-frame JavaScript snippet into sub-frame (iframe) HTML
 * responses so it runs inside cross-origin iframes that the main frame's
 * `evaluateJavascript` cannot reach.
 *
 * Two paths:
 *  - If [SniBypassClient] already produced a response for this request, wrap
 *    its stream to splice the script in before `</body>`.
 *  - Otherwise, fetch the resource ourselves through OkHttp using cookies
 *    from the WebView's CookieManager.
 *
 * We only act on sub-frame `GET` requests whose Accept / Sec-Fetch-Dest
 * indicates an iframe document, so normal subresources (CSS/JS/XHR) are
 * untouched. Inline script CSP headers are stripped so the injected
 * `<script>` is allowed to execute.
 */
object IframeScriptInjector {

    @Volatile private var script: String = ""

    fun setScript(s: String) { script = s }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun process(
        request: WebResourceRequest,
        upstream: WebResourceResponse?
    ): WebResourceResponse? {
        if (script.isEmpty()) return upstream
        if (request.isForMainFrame) return upstream
        val method = request.method?.uppercase() ?: "GET"
        if (method != "GET") return upstream
        if (!looksLikeIframeDoc(request)) return upstream

        if (upstream != null) {
            val mime = upstream.mimeType?.lowercase().orEmpty()
            if (!mime.contains("html")) return upstream
            return wrapResponse(upstream) ?: upstream
        }

        val url = request.url ?: return null
        val scheme = url.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        return fetchAndInject(request)
    }

    private fun looksLikeIframeDoc(request: WebResourceRequest): Boolean {
        val headers = request.requestHeaders ?: return false
        for ((k, v) in headers) {
            if (k.equals("Sec-Fetch-Dest", ignoreCase = true)) {
                return v.equals("iframe", ignoreCase = true) ||
                    v.equals("frame", ignoreCase = true) ||
                    v.equals("document", ignoreCase = true)
            }
        }
        // Fallback for older WebViews without Sec-Fetch-Dest.
        val accept = headers.entries
            .firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value?.lowercase().orEmpty()
        return accept.contains("text/html")
    }

    private fun fetchAndInject(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return null
        val urlStr = url.toString()
        return try {
            val builder = Request.Builder().url(urlStr)
            request.requestHeaders?.forEach { (k, v) ->
                if (k.equals("host", ignoreCase = true)) return@forEach
                if (k.equals("connection", ignoreCase = true)) return@forEach
                if (k.equals("cookie", ignoreCase = true)) return@forEach
                if (k.equals("accept-encoding", ignoreCase = true)) return@forEach
                if (k.startsWith(":")) return@forEach
                runCatching { builder.header(k, v) }
            }
            val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
            cookieMgr?.getCookie(urlStr)?.takeIf { it.isNotBlank() }?.let {
                builder.header("Cookie", it)
            }

            client.newCall(builder.build()).execute().use { resp ->
                val ctRaw = resp.header("Content-Type")
                val mime = ctRaw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                if (!mime.contains("html")) return null

                val bytes = resp.body?.bytes() ?: byteArrayOf()
                // Header charset wins; otherwise sniff the document's own
                // <meta charset> (Korean pages often omit the HTTP charset and
                // declare EUC-KR/CP949 only in the HTML). Falling straight to
                // UTF-8 would corrupt those bytes on decode + re-encode.
                val charsetName = parseCharset(ctRaw) ?: sniffMetaCharset(bytes) ?: "UTF-8"
                val headers = LinkedHashMap<String, String>()
                resp.headers.forEach { (n, v) ->
                    if (n.equals("Content-Encoding", true)) return@forEach
                    if (n.equals("Content-Length", true)) return@forEach
                    if (n.equals("Transfer-Encoding", true)) return@forEach
                    if (n.equals("Content-Security-Policy", true)) return@forEach
                    if (n.equals("Content-Security-Policy-Report-Only", true)) return@forEach
                    if (n.equals("Set-Cookie", true)) {
                        cookieMgr?.setCookie(urlStr, v); return@forEach
                    }
                    headers[n] = v
                }

                val charset = runCatching { Charset.forName(charsetName) }
                    .getOrDefault(Charsets.UTF_8)
                val outBytes = injectScript(String(bytes, charset)).toByteArray(charset)
                WebResourceResponse(
                    "text/html",
                    charsetName,
                    resp.code,
                    resp.message.ifBlank { "OK" },
                    headers,
                    ByteArrayInputStream(outBytes)
                )
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun wrapResponse(upstream: WebResourceResponse): WebResourceResponse? {
        return try {
            val stream = upstream.data ?: return null
            val bytes = stream.use { it.readBytes() }
            val charsetName = upstream.encoding?.ifBlank { null }
                ?: sniffMetaCharset(bytes) ?: "UTF-8"
            val charset = runCatching { Charset.forName(charsetName) }
                .getOrDefault(Charsets.UTF_8)
            val outBytes = injectScript(String(bytes, charset)).toByteArray(charset)

            val sanitizedHeaders = upstream.responseHeaders?.let { src ->
                LinkedHashMap<String, String>().apply {
                    src.forEach { (n, v) ->
                        if (n.equals("Content-Security-Policy", true)) return@forEach
                        if (n.equals("Content-Security-Policy-Report-Only", true)) return@forEach
                        if (n.equals("Content-Length", true)) return@forEach
                        put(n, v)
                    }
                }
            }

            WebResourceResponse(
                upstream.mimeType ?: "text/html",
                charsetName,
                upstream.statusCode,
                upstream.reasonPhrase?.ifBlank { "OK" } ?: "OK",
                sanitizedHeaders,
                ByteArrayInputStream(outBytes)
            )
        } catch (e: Throwable) {
            null
        }
    }

    private fun injectScript(html: String): String {
        val tag = "<script>" + script + "</script>"
        val idx = html.lastIndexOf("</body>", ignoreCase = true)
        return if (idx >= 0) html.substring(0, idx) + tag + html.substring(idx) else html + tag
    }

    /**
     * Sniffs the charset from an HTML document's <meta> declarations, scanning
     * only the head region. Handles both forms:
     *   <meta charset="euc-kr">
     *   <meta http-equiv="Content-Type" content="text/html; charset=euc-kr">
     * The prefix is decoded as ISO-8859-1 so every byte maps to one char —
     * charset names are ASCII, so this is safe regardless of the real encoding.
     */
    private fun sniffMetaCharset(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val len = minOf(bytes.size, 4096)
        val head = String(bytes, 0, len, Charsets.ISO_8859_1)
        val match = META_CHARSET.find(head) ?: return null
        return match.groupValues[1].trim().trim('"', '\'').ifBlank { null }
    }

    private val META_CHARSET =
        Regex("""charset\s*=\s*["']?\s*([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)

    private fun parseCharset(contentType: String?): String? {
        if (contentType == null) return null
        for (part in contentType.split(';')) {
            val t = part.trim()
            if (t.startsWith("charset=", ignoreCase = true)) {
                return t.substringAfter('=').trim().ifBlank { null }
            }
        }
        return null
    }
}
