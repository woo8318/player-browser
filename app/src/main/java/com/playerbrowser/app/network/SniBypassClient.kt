package com.playerbrowser.app.network

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
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

    private fun build(): OkHttpClient {
        val fragmenting = FragmentingSocketFactory()
        return OkHttpClient.Builder()
            .dns(DohClient())
            .socketFactory(fragmenting)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!SniBypassSwitch.enabled) return null
        if (!request.isForMainFrame) return null
        val method = request.method?.uppercase() ?: "GET"
        if (method != "GET") return null
        val url = request.url ?: return null
        val scheme = url.scheme?.lowercase() ?: return null
        if (scheme != "https") return null

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
