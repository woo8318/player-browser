package com.playerbrowser.app.network

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * "URL의 숫자가 바뀌었을 가능성" 복구기.
 *
 * 메인 프레임이 접속 자체에 실패하면(DNS/연결/타임아웃/리셋), URL 안의 숫자를
 * 증감한 후보들을 만들어 백그라운드로 접속 가능 여부를 확인한다. 살아있는 후보를
 * 찾으면 그 URL로 자동 이동한다.
 *
 * 주 용도: newtoki/마나토끼류처럼 도메인 끝 숫자가 주기적으로 바뀌는 사이트
 * (newtoki123.com → newtoki124.com). 도메인에 숫자가 없으면 경로/쿼리의
 * 마지막 숫자로 폴백한다.
 */
object UrlRecovery {

    // 다음 숫자부터 +10, 그 다음 -1~-3. 가까운 후보가 먼저 오도록 정렬해 두면
    // 동시에 프로브한 뒤 가장 앞선(가까운) 성공 후보를 고를 수 있다.
    private val OFFSETS: List<Int> = (1..10).toList() + listOf(-1, -2, -3)

    // 접속 실패성 에러 코드만 복구 대상. -10(unsupported scheme), -12(bad url),
    // -14(file not found) 등은 숫자를 바꿔도 의미가 없으므로 제외.
    // -1 UNKNOWN, -2 HOST_LOOKUP, -6 CONNECT, -7 IO, -8 TIMEOUT,
    // -11 SSL_HANDSHAKE, -16 CONNECTION_RESET.
    private val PROBE_CODES = setOf(-1, -2, -6, -7, -8, -11, -16)

    private const val PROBE_TIMEOUT_MS = 8_000L
    private const val TRIED_TTL_MS = 60_000L
    private const val NAV_WINDOW_MS = 120_000L
    private const val MAX_NAV_IN_WINDOW = 5

    // WebView가 쓰는 Chrome UA에 가깝게. 본문은 읽지 않고 응답 코드만 본다.
    private const val PROBE_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 이미 프로브한 후보 URL(생존/사망 무관) + 실패한 원본 URL → 타임스탬프.
    // TTL 동안 후보 생성에서 제외해 (1) 같은 죽은 후보를 반복 프로브하지 않고
    // (2) A↔B 오실레이션을 막는다.
    private val probed = ConcurrentHashMap<String, Long>()

    // 자동 이동(=프로브 착수) 타임스탬프 링. 짧은 시간 내 폭주를 막는 백스톱.
    private val navTimes = ArrayDeque<Long>()

    /** SNI 우회 경로(DoH + ClientHello 단편화). WebView가 우회 모드일 때 동일 경로로 확인. */
    private val bypassClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DohClient())
            .socketFactory(FragmentingSocketFactory())
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            // 리다이렉트를 따라가지 않는다 — 죽은/파킹된 도메인이 광고·매물 페이지로
            // 302 후 200을 주는 걸 "생존"으로 오판하지 않기 위함. 실제 이동은 WebView가
            // 정상 로드하며 알아서 따라간다.
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 일반 경로. SNI 우회가 꺼져 있으면 WebView도 일반 경로로 로드하므로 일치시킨다. */
    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun shouldProbe(code: Int): Boolean = code in PROBE_CODES

    /**
     * 후보 URL 목록(가까운 숫자 순). 도메인 숫자 우선, 없으면 경로, 그 다음 쿼리.
     * 원본/최근 프로브한 후보는 제외한다.
     */
    fun candidates(failingUrl: String): List<String> {
        val uri = runCatching { Uri.parse(failingUrl) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return emptyList()
        val host = uri.host ?: return emptyList()

        val out = LinkedHashSet<String>()

        // 1) 도메인 숫자 (IP 리터럴은 제외)
        if (!isIpLiteral(host)) {
            lastDigitRun(host)?.let { run ->
                for (nd in numberVariants(run)) {
                    out += rebuildHost(uri, replaceRun(host, run, nd))
                }
            }
        }
        // 2) 폴백: 경로 숫자
        if (out.isEmpty()) {
            val path = uri.encodedPath
            if (!path.isNullOrEmpty()) {
                lastDigitRun(path)?.let { run ->
                    for (nd in numberVariants(run)) {
                        out += uri.buildUpon().encodedPath(replaceRun(path, run, nd)).build().toString()
                    }
                }
            }
        }
        // 3) 폴백: 쿼리 숫자
        if (out.isEmpty()) {
            val query = uri.encodedQuery
            if (!query.isNullOrEmpty()) {
                lastDigitRun(query)?.let { run ->
                    for (nd in numberVariants(run)) {
                        out += uri.buildUpon().encodedQuery(replaceRun(query, run, nd)).build().toString()
                    }
                }
            }
        }

        out.remove(failingUrl)
        val now = System.currentTimeMillis()
        return out.filterNot { isRecentlyProbed(it, now) }
    }

    /**
     * 후보들을 동시에 프로브해 가장 가까운 살아있는 URL을 [onResult]로 돌려준다.
     * 없으면 null. 콜백은 백그라운드 스레드에서 호출되므로 UI 작업은 호출 측에서 post.
     *
     * [candidateUrls]는 [candidates]로 미리 계산한 목록을 그대로 넘긴다(중복 계산 회피).
     */
    fun probe(failingUrl: String, candidateUrls: List<String>, onResult: (String?) -> Unit) {
        if (candidateUrls.isEmpty()) {
            onResult(null)
            return
        }
        // 예산은 프로브 착수 시점에 선점한다 — 여러 탭이 동시에 실패해도 in-flight
        // 프로브 수가 한도를 넘지 않도록.
        if (!reserveNavSlot()) {
            DebugLog.w("UrlRecovery", "auto-nav budget exhausted; skip probe for $failingUrl")
            onResult(null)
            return
        }
        DebugLog.d("UrlRecovery", "probing ${candidateUrls.size} candidates for $failingUrl")
        scope.launch {
            val found = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                candidateUrls.map { c -> async { c to reachable(c) } }
                    .awaitAll()
                    .firstOrNull { it.second }
                    ?.first
            }
            // 프로브한 후보 전부 + 실패한 원본을 TTL 동안 제외해 재프로브/오실레이션 차단.
            markProbed(candidateUrls)
            markProbed(listOf(failingUrl))
            DebugLog.d("UrlRecovery", if (found != null) "alive candidate: $found" else "no alive candidate for $failingUrl")
            onResult(found)
        }
    }

    private fun reachable(url: String): Boolean {
        val client = if (SniBypassSwitch.enabled) bypassClient else plainClient
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header("User-Agent", PROBE_UA)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .build()
        }.getOrNull() ?: return false
        val host = request.url.host
        return runCatching {
            client.newCall(request).execute().use { resp -> isAlive(resp, host) }
        }.getOrDefault(false)
    }

    // 살아있는 서버로 간주하는 응답. 죽은/이동한 도메인은 HTTP 응답 자체가 없거나
    // (연결 에러) 404/410/5xx를 준다. 401/403/429는 서버는 살아있고 게이트/챌린지만
    // 있는 경우라 이동 대상으로 인정. 3xx는 동일 호스트(슬래시 보정 등)로 가는 경우만
    // 인정하고, 다른 호스트로 빠지는 리다이렉트(파킹/매물 페이지 의심)는 제외한다.
    private fun isAlive(resp: Response, requestHost: String): Boolean {
        val code = resp.code
        return when {
            code in 200..299 -> true
            code in 300..399 -> {
                val location = resp.header("Location") ?: return true
                val locHost = location.toHttpUrlOrNull()?.host
                locHost == null || locHost.equals(requestHost, ignoreCase = true)
            }
            code == 401 || code == 403 || code == 429 -> true
            else -> false
        }
    }

    // --- 숫자 추출 / 치환 ---

    private data class DigitRun(val start: Int, val end: Int, val text: String)

    private fun lastDigitRun(s: String): DigitRun? {
        var i = s.length - 1
        while (i >= 0 && !s[i].isDigit()) i--
        if (i < 0) return null
        val end = i + 1
        while (i >= 0 && s[i].isDigit()) i--
        val start = i + 1
        return DigitRun(start, end, s.substring(start, end))
    }

    private fun numberVariants(run: DigitRun): List<String> {
        val base = run.text.toLongOrNull() ?: return emptyList()
        val width = run.text.length
        val padded = run.text.startsWith("0") && width > 1
        return OFFSETS.mapNotNull { off ->
            val v = base + off
            if (v < 0) null
            else if (padded) v.toString().padStart(width, '0')
            else v.toString()
        }
    }

    private fun replaceRun(s: String, run: DigitRun, newDigits: String): String =
        s.substring(0, run.start) + newDigits + s.substring(run.end)

    private fun rebuildHost(uri: Uri, newHost: String): String {
        val authority = buildString {
            uri.userInfo?.takeIf { it.isNotEmpty() }?.let { append(it).append('@') }
            append(newHost)
            if (uri.port != -1) append(':').append(uri.port)
        }
        return uri.buildUpon().encodedAuthority(authority).build().toString()
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true // IPv6
        val parts = host.split('.')
        return parts.size == 4 && parts.all { (it.toIntOrNull() ?: -1) in 0..255 }
    }

    // --- 오실레이션 / 폭주 가드 ---

    private fun isRecentlyProbed(url: String, now: Long): Boolean {
        val t = probed[url] ?: return false
        if (now - t > TRIED_TTL_MS) {
            probed.remove(url)
            return false
        }
        return true
    }

    private fun markProbed(urls: Collection<String>) {
        val now = System.currentTimeMillis()
        urls.forEach { probed[it] = now }
    }

    @Synchronized
    private fun reserveNavSlot(): Boolean {
        val now = System.currentTimeMillis()
        while (navTimes.isNotEmpty() && now - navTimes.peekFirst() > NAV_WINDOW_MS) {
            navTimes.pollFirst()
        }
        if (navTimes.size >= MAX_NAV_IN_WINDOW) return false
        navTimes.addLast(now)
        return true
    }
}
