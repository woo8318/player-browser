package com.playerbrowser.app.network

import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * WebView 기본 User-Agent를 **평범한 Chrome for Android UA로 정규화**한다.
 *
 * Android WebView의 기본 UA에는 자신이 WebView임을 그대로 드러내는 토큰이
 * 들어 있다:
 *
 * ```
 * Mozilla/5.0 (Linux; Android 14; SM-S926N Build/UP1A.231005.007; wv)
 *   AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0
 *   Chrome/120.0.6099.230 Mobile Safari/537.36
 * ```
 *
 * `; wv` 와 `Version/4.0` 이 그 표식이다(진짜 Chrome UA엔 둘 다 없다).
 * Cloudflare 같은 안티봇은 이 토큰만으로 "인앱 브라우저/WebView"로 분류하는데,
 * 보안 등급이 높게 설정된 사이트는 WebView에 **통과 점수를 아예 주지 않는다** —
 * 체크박스를 눌러 챌린지를 풀어도 브라우저 환경 점수에서 떨어져 `cf_clearance`가
 * 곧바로 무효가 되고 다시 챌린지가 뜬다. 같은 사이트가 오페라/크롬에선 한 번에
 * 열리는데 우리만 무한 반복되던 이유(v1.3.54~57에서 네트워크 경로를 아무리
 * 일치시켜도 남아 있던 마지막 차이).
 *
 * 여기서는 **토큰을 빼기만 한다** — 버전·기기명·Chrome 빌드번호는 그대로 두므로
 * 결과 문자열은 같은 기기의 실제 Chrome UA와 형태가 일치한다. 예전에 UA 뒤에
 * 임의 접미사를 붙였다가 `ERR_CONNECTION_RESET`을 맞았던 것과는 반대 방향의
 * 변경(비표준 → 표준)이라 위험이 낮다. `Build/`도 함께 제거 — Chrome UA엔 없고
 * 기기 빌드 지문을 흘리지 않는 부수 효과도 있다.
 */
object UserAgentSpoof {

    private const val TAG = "UserAgent"

    @Volatile private var logged = false

    /** WebView 기본 UA → Chrome for Android UA. 형태가 예상과 다르면 원본 유지. */
    fun chromeLike(default: String?): String {
        val src = default.orEmpty()
        if (src.isBlank()) return src
        var out = src
        // "SM-S926N Build/UP1A.231005.007; wv)" → "SM-S926N)"
        out = out.replace(Regex("""\s*Build/[^;)]*"""), "")
        out = out.replace("; wv", "").replace(";wv", "")
        // WebView만 붙이는 "Version/4.0".
        out = out.replace(Regex("""\sVersion/\d+(\.\d+)*"""), "")
        out = out.replace(Regex("""\s{2,}"""), " ").trim()
        // 알 수 없는 형태로 망가졌으면 손대지 않는다.
        if (!out.contains("Chrome/") || !out.contains("Mozilla/5.0")) return src
        if (!logged) {
            logged = true
            DebugLog.d(TAG, "UA 정규화: $src")
            DebugLog.d(TAG, "        → $out")
        }
        return out
    }

    /**
     * UA 문자열과 **짝을 맞춰** UA Client Hints(`Sec-CH-UA`)도 Chrome으로 맞춘다.
     *
     * [chromeLike]만 적용하면 UA 문자열은 "Chrome"이라고 하는데 `Sec-CH-UA`는
     * 여전히 `"Android WebView";v="150"`을 보낸다. 안티봇은 이 둘을 교차 검증하므로
     * **어긋난 상태가 정직한 WebView보다 더 나쁜 신호**가 된다 — v1.3.58에서 UA만
     * 바꿨는데도 Cloudflare 챌린지가 그대로 반복된 이유로 의심되는 부분.
     *
     * 그래서 브랜드 목록만 평범한 Chrome 조합으로 갈아끼운다. 플랫폼/기기/버전 등
     * 나머지 필드는 기존 메타데이터를 그대로 복사해 UA 문자열과 계속 일치시킨다.
     * 기기가 이 API를 지원하지 않으면(구형 WebView) 조용히 넘어간다 — 그 경우
     * `Sec-CH-UA` 자체를 안 보내는 버전이라 어긋남도 없다.
     */
    fun applyClientHints(settings: WebSettings, ua: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val full = CHROME_VERSION.find(ua)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: return
        val major = full.substringBefore('.')
        runCatching {
            val brands = listOf(
                brand("Chromium", major, full),
                brand("Google Chrome", major, full),
                // GREASE 항목 — 진짜 Chrome도 파서 관용성 확인용으로 하나 끼워 보낸다.
                brand("Not)A;Brand", "8", "8.0.0.0")
            )
            val current = WebSettingsCompat.getUserAgentMetadata(settings)
            val updated = UserAgentMetadata.Builder(current)
                .setBrandVersionList(brands)
                .build()
            WebSettingsCompat.setUserAgentMetadata(settings, updated)
            DebugLog.d(TAG, "Sec-CH-UA 브랜드 → Chromium/Google Chrome $major (WebView 표식 제거)")
        }.onFailure {
            DebugLog.w(TAG, "Sec-CH-UA 조정 실패(무시): ${it.javaClass.simpleName}")
        }
    }

    private fun brand(name: String, major: String, full: String): UserAgentMetadata.BrandVersion =
        UserAgentMetadata.BrandVersion.Builder()
            .setBrand(name)
            .setMajorVersion(major)
            .setFullVersion(full)
            .build()

    private val CHROME_VERSION = Regex("""Chrome/([0-9][0-9.]*)""")
}
