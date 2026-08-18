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

        // 챌린지를 내려준 적이 있는 호스트는 **메인 프레임 문서까지 포함해** 통째로
        // 네이티브 로더에 맡긴다. 검증 POST는 어차피 우리 손이 닿지 않으므로,
        // 문서만 우리 OkHttp로 받으면 챌린지를 푼 지문과 문서를 받은 지문이 갈려
        // `cf_clearance`가 무효화되고 캡차가 무한 반복된다.
        if (ChallengeDetector.isQuarantinedHost(host)) {
            ChallengeDetector.noteSkipped("sni-격리", url)
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
            val response = execute(client, builder.build(), request.isForMainFrame, host)
            resp = response

            // 이 응답이 Cloudflare 챌린지 페이지면 우리가 잡고 있을수록 손해다 —
            // 호스트를 격리하고 null을 돌려줘 WebView가 처음부터 네이티브로
            // 다시 요청하게 한다(챌린지 GET/POST/재로드가 한 경로에서 돈다).
            if (request.isForMainFrame &&
                ChallengeDetector.isChallengeResponse(response.code) { response.header(it) }
            ) {
                val hasClearance =
                    cookieManager?.getCookie(urlString)?.contains("cf_clearance") == true
                ChallengeDetector.markChallengedHost(
                    host,
                    "응답 ${response.code}, cf-mitigated=${response.header("cf-mitigated")}, " +
                        "cf_clearance 보유=$hasClearance"
                )
                if (host.isNotBlank()) bypassedHosts.remove(host)
                response.close()
                return null
            }

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
            if (request.isForMainFrame) {
                DebugLog.d("SniBypass", "intercept ok: $host -> ${resp.code}")
                // 메인 프레임 3xx는 우리가 따라가지 않고 WebView에 넘긴다(주소창
                // 동기화 때문). 리다이렉트 목적지를 남겨 두면 "빈 화면으로 멈춤"
                // 같은 증상이 왔을 때 여기서 끊긴 건지 바로 알 수 있다.
                if (resp.code in 300..399) {
                    DebugLog.d("SniBypass", "  main-frame redirect → ${resp.header("Location")}")
                }
            }
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

    /**
     * 메인 프레임은 실패하면 **즉시 한 번 재시도**한다. ClientHello 단편화가
     * DPI를 뚫는지는 확률적이라(미들박스의 재조립 타이밍·경로에 따라 갈린다)
     * 한 번 실패했다고 null을 돌려주면 WebView가 단편화 없는 네이티브 로더로
     * 떨어져 그대로 차단당한다 — "사이트가 한 번에 안 열리고 새로고침하면
     * 열리는" 증상의 마지막 조각. 재시도는 새 커넥션(메인 프레임 풀은 5초라
     * 죽은 커넥션이 남지 않음)으로 나가므로 단편화를 다시 시도하게 된다.
     *
     * 서브리소스는 재시도하지 않는다 — 한 CDN 호스트로 이미지 수십 개가
     * 몰리는 구간이라 재시도가 곱해지면 오히려 전체가 밀린다.
     */
    private fun execute(
        client: OkHttpClient,
        req: Request,
        mainFrame: Boolean,
        host: String
    ): Response {
        try {
            return client.newCall(req).execute()
        } catch (e: IOException) {
            if (!mainFrame) throw e
            DebugLog.w("SniBypass", "main-frame 1차 실패 → 재시도: $host", e)
            // Call은 1회용이라 새로 만든다.
            return client.newCall(req).execute()
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
