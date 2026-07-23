package com.playerbrowser.app.network

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches just the `<title>` of a page so a *background* tab (link long-press
 * "open in background") can show what it is in the tab switcher without building
 * or activating its WebView. Background tabs load lazily on first switch, so
 * until then their card would only show the bare host ("youtube.com") — useless
 * for telling several backgrounded pages apart. This grabs a real title cheaply.
 *
 * Strictly best-effort: any failure returns null and the card keeps its host
 * placeholder. Reads only a bounded prefix of the HTML (the <title> lives in
 * <head>, near the top) so we never pull a whole page for a label.
 */
object TabTitlePrefetcher {

    // Mirror the WebView's request routing so the prefetch succeeds on the same
    // sites the browser can reach: SNI bypass → ClientHello fragmentation + DoH,
    // Private DNS only → plain socket + DoH, neither → plain socket + system DNS.
    private val fragClient: OkHttpClient by lazy { build(fragment = true, doh = true) }
    private val dohClient: OkHttpClient by lazy { build(fragment = false, doh = true) }
    private val systemClient: OkHttpClient by lazy { build(fragment = false, doh = false) }

    private const val MAX_HTML_BYTES = 128 * 1024
    private val TITLE_REGEX = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val META_CHARSET_REGEX = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)

    private fun build(fragment: Boolean, doh: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        if (doh) builder.dns(DohClient())
        if (fragment) builder.socketFactory(FragmentingSocketFactory())
        return builder.build()
    }

    /**
     * Returns the page's title, or null on any failure / when the URL isn't
     * http(s). Runs on IO. [userAgent] should be the WebView's UA so protected
     * sites answer the same way they would in the browser.
     */
    suspend fun fetchTitle(url: String, userAgent: String?): String? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http", ignoreCase = true)) return@withContext null
        val client = when {
            SniBypassSwitch.enabled -> fragClient
            PrivateDnsSwitch.enabled -> dohClient
            else -> systemClient
        }
        val builder = Request.Builder().url(url)
        userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        return@withContext try {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null
                val bytes = body.byteStream().use { readPrefix(it, MAX_HTML_BYTES) }
                val charset = resp.header("Content-Type")
                    ?.let { META_CHARSET_REGEX.find(it)?.groupValues?.getOrNull(1) }
                    ?: sniffMetaCharset(bytes)
                val html = decode(bytes, charset)
                extractTitle(html)
            }
        } catch (t: Throwable) {
            DebugLog.w("TitlePrefetch", "fetch failed: $url", t)
            null
        }
    }

    private fun readPrefix(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun sniffMetaCharset(bytes: ByteArray): String? {
        // ASCII-decode the prefix just to find a <meta charset> declaration.
        val head = String(bytes, Charsets.ISO_8859_1)
        return META_CHARSET_REGEX.find(head)?.groupValues?.getOrNull(1)
    }

    private fun decode(bytes: ByteArray, charset: String?): String =
        runCatching { charset?.let { charset(it) } }.getOrNull()
            ?.let { String(bytes, it) }
            ?: String(bytes, Charsets.UTF_8)

    private fun extractTitle(html: String): String? {
        val raw = TITLE_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val text = decodeEntities(raw).replace(Regex("\\s+"), " ").trim()
        return text.ifBlank { null }
    }

    /** Minimal HTML-entity decoding for the handful common in titles. */
    private fun decodeEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
}
