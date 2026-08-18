package com.playerbrowser.app.network

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
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

    // OkHttp clients are matrixed on two independent axes:
    //
    //  - fragment: when SNI bypass is on we splinter the TLS ClientHello via
    //    FragmentingSocketFactory to defeat DPI. When only Private DNS is on we
    //    use a plain socket — fragmentation is unnecessary and a compat risk if
    //    the user didn't ask for SNI evasion.
    //  - followRedirects: main-frame requests never follow redirects (OkHttp
    //    resolving them internally would desync the WebView's address bar /
    //    origin); subresource requests DO, because the WebView does not follow
    //    redirects returned from an intercepted *subresource* response, so a
    //    same-host image/CSS/JS answering 301/302 (signed CDN URL, http->https,
    //    extension rewrite, ...) would otherwise silently fail to load.
    //
    // All four share the configurable DoH resolver (PrivateDnsSwitch.dohUrl).
    private val fragMainClient: OkHttpClient by lazy { build(fragment = true, followRedirects = false) }
    private val fragSubClient: OkHttpClient by lazy { build(fragment = true, followRedirects = true) }
    private val plainMainClient: OkHttpClient by lazy { build(fragment = false, followRedirects = false) }
    private val plainSubClient: OkHttpClient by lazy { build(fragment = false, followRedirects = true) }

    /**
     * Hosts whose main frame we already fetched over OkHttp (SNI bypass OR
     * Private DNS). Subresource requests to these hosts are also routed through
     * OkHttp so the rest of the page (CSS / JS / images / XHR / iframes) gets
     * the same treatment — DPI evasion under SNI bypass, the chosen DoH resolver
     * under Private DNS — instead of falling back to the system path.
     */
    private val bypassedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun build(fragment: Boolean, followRedirects: Boolean): OkHttpClient {
        // followRedirects is our 1:1 proxy for "subresource client" (main-frame
        // clients never follow redirects). Subresource loads come in bursts —
        // a webtoon page fires dozens of CDN image requests at once — and since
        // SNI bypass now routes ALL of them through OkHttp, a tiny pool would
        // serialize the burst. Give subresources a larger, longer-lived pool to
        // keep parallelism close to the native loader. Main-frame clients keep
        // the tiny 5s pool: a connection the middlebox silently RSTs can't sit
        // around long enough to poison the next page navigation.
        val pool = if (followRedirects) ConnectionPool(8, 30, TimeUnit.SECONDS)
                   else ConnectionPool(4, 5, TimeUnit.SECONDS)
        val builder = OkHttpClient.Builder()
            .dns(DohClient())
            .connectionPool(pool)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .retryOnConnectionFailure(true)
        if (fragment) builder.socketFactory(FragmentingSocketFactory())
        if (followRedirects) {
            // Subresource clients face image bursts that hit a SINGLE CDN host
            // (a webtoon chapter pulls every panel from one image server).
            // OkHttp's default maxRequestsPerHost = 5 would serialize that into
            // slow waves; raise it so concurrency tracks the native loader.
            builder.dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            })
        }
        return builder.build()
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val sni = SniBypassSwitch.enabled
        val privateDns = PrivateDnsSwitch.enabled
        // Either feature routes requests through our OkHttp path: SNI bypass
        // adds ClientHello fragmentation, Private DNS just swaps the resolver.
        if (!sni && !privateDns) return null
        val method = request.method?.uppercase() ?: "GET"
        if (method != "GET" && method != "HEAD") return null
        val url = request.url ?: return null
        val scheme = url.scheme?.lowercase() ?: return null
        if (scheme != "https") return null
        val host = url.host?.lowercase().orEmpty()

        // 안티봇 챌린지(캡차)는 절대 가로채지 않는다. shouldInterceptRequest는
        // POST 본문을 볼 수 없어 챌린지 GET만 우리 OkHttp로 오고 검증 POST는
        // 네이티브로 나가는데, 그러면 커넥션/DNS 경로가 갈라져 챌린지가 영영
        // 완료되지 않는다(체크해도 "사람인지 확인" 화면만 반복). 흐름 전체를
        // WebView 네이티브 로더 한 곳에 맡긴다.
        if (ChallengeDetector.isChallengeRequest(url)) {
            ChallengeDetector.noteSkipped("sni", url)
            return null
        }

        // Main-frame requests are always intercepted (cheap insurance for any
        // host that turns out to be DPI-blocked). Subresource gating:
        //  - same-host of a bypassed page (`bypassedHosts`): unchanged — routed
        //    through OkHttp under SNI bypass OR Private DNS, including range/media
        //    requests (this is how blocked-site video already works).
        //  - cross-host, SNI bypass ON: also intercept, so image/media CDNs
        //    referenced by a bypassed page (e.g. a webtoon page on blacktoon*.com
        //    pulling images from koimagemoa.com / webimg7.com — never main frames,
        //    themselves DPI-blocked) get ClientHello fragmentation instead of the
        //    exposed native handshake that left their images blank. BUT skip
        //    `Range` (media) requests here: video seeking / progressive playback
        //    rely on the native loader's 206 handling and mid-stream resilience,
        //    which our streaming wrapper can't match once headers are committed.
        //    Images/CSS/JS carry no Range, so the fix still lands.
        //  - cross-host, Private-DNS-only: skip. DNS needs no fragmentation and
        //    funneling every third-party request through OkHttp isn't worth it.
        if (!request.isForMainFrame) {
            val sameHostBypassed = host.isNotBlank() && host in bypassedHosts
            if (!sameHostBypassed) {
                if (!sni) return null
                val isRange = request.requestHeaders?.keys?.any { it.equals("Range", true) } == true
                if (isRange) return null
            }
        }

        val mode = if (sni) "sni" else "dns"
        if (request.isForMainFrame) DebugLog.d("SniBypass", "intercept[$mode]: $method $host (main frame)")

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

        // Fragment only when SNI bypass intends DPI evasion; Private-DNS-only
        // requests use the plain (non-fragmenting) clients.
        val client = when {
            request.isForMainFrame && sni -> fragMainClient
            request.isForMainFrame -> plainMainClient
            sni -> fragSubClient
            else -> plainSubClient
        }
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
            var sawSetCookie = false
            resp.headers.forEach { (name, value) ->
                if (name.equals("Content-Encoding", true)) return@forEach
                if (name.equals("Content-Length", true)) return@forEach
                if (name.equals("Transfer-Encoding", true)) return@forEach
                if (name.equals("Set-Cookie", true)) {
                    cookieManager?.setCookie(urlString, value)
                    sawSetCookie = true
                    return@forEach
                }
                headers[name] = value
            }
            // setCookie로 심은 쿠키는 WebView가 알아서 디스크에 쓰지 않는다 —
            // 명시적으로 flush해야 프로세스가 죽어도 살아남는다(cf_clearance 등).
            if (sawSetCookie) CookieFlusher.schedule()
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
