package com.playerbrowser.app.network

import android.webkit.CookieManager

/**
 * Cloudflare 챌린지 관련 쿠키 정리 + 진단.
 *
 * v1.3.54~55에서 [SniBypassClient] / [com.playerbrowser.app.web.IframeScriptInjector]는
 * 가로챈 응답의 `Set-Cookie`를 헤더에서 떼어 [CookieManager.setCookie]로 직접
 * 심고 [CookieFlusher]로 디스크에 영속화했다. 그렇게 심은 쿠키는 도메인 스코프가
 * 네이티브 로더가 심는 것과 어긋날 수 있어(`example.com` vs `.example.com`)
 * **같은 이름의 `cf_clearance`가 두 벌** 남을 수 있다. 그러면 이후 요청에 통과
 * 토큰이 두 개 실려 Cloudflare가 거부한다.
 *
 * **삭제 범위를 두 단계로 나눈다 (v1.3.58).** v1.3.57은 한 함수로 `__cf_bm`과
 * `cf_chl*`까지 함께 지웠는데, 이 둘은 **지금 돌고 있는 챌린지의 진행 상태**다.
 * 챌린지 화면이 뜬 뒤(=`onPageFinished` DOM 감지, 루프 감지) 이것들을 지우면
 * 진행 중인 흐름을 우리가 끊어버려 오히려 통과가 불가능해진다. 그래서
 *
 *  - [resetAll] — 챌린지가 **시작되기 전**(403 응답을 버리고 네이티브로
 *    재요청시키는 순간)에만. 이때는 진행 중인 흐름이 없다.
 *  - [resetClearance] — 흐름이 도는 중에는 **통과 토큰만**. 이미 거부된 낡은
 *    토큰이라 지워도 손해가 없고, 통과하면 곧바로 새로 발급된다.
 *
 * 사이트 자체 로그인/세션 쿠키는 어느 쪽도 건드리지 않는다.
 */
object ChallengeCookies {

    private const val TAG = "Captcha"

    /** 통과 토큰 — 낡으면 무조건 방해만 된다. */
    private const val CLEARANCE = "cf_clearance"

    /** 챌린지 **진행 상태** 쿠키 — 흐름이 도는 중엔 절대 건드리면 안 된다. */
    private val IN_FLIGHT = listOf("__cf_bm", "__cfruid", "__cfwaitingroom")

    private fun isInFlight(name: String): Boolean =
        IN_FLIGHT.any { name.equals(it, ignoreCase = true) } ||
            name.startsWith("cf_chl", ignoreCase = true)

    /** 통과 토큰만 만료. 챌린지가 돌고 있는 중에도 안전하다. */
    fun resetClearance(host: String?) = expire(host) { it.equals(CLEARANCE, ignoreCase = true) }

    /** 통과 토큰 + 진행 상태까지 전부 만료. 챌린지 **시작 전**에만 쓸 것. */
    fun resetAll(host: String?) =
        expire(host) { it.equals(CLEARANCE, ignoreCase = true) || isInFlight(it) }

    private fun expire(host: String?, match: (String) -> Boolean) {
        val h = host?.lowercase()?.trim().orEmpty()
        if (h.isBlank()) return
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        val url = "https://$h/"

        val names = namesOf(cm, url).filter(match).distinct()
        if (names.isEmpty()) return

        // 도메인 스코프를 모르므로 있을 법한 조합을 모두 만료시킨다 — 정확한
        // 스코프로 만료시키지 않으면 그 쿠키만 살아남아 오염이 그대로 남는다.
        val bare = h.removePrefix("www.")
        val domains = linkedSetOf("", h, ".$h", bare, ".$bare")
        for (name in names) {
            for (d in domains) {
                val suffix = if (d.isBlank()) "" else "; Domain=$d"
                // `Secure` 를 빼면 Secure 로 심긴 원본을 못 덮어쓸 수 있다
                // (v1.3.68 로그: 삭제 직후에도 같은 토큰이 그대로 읽혔다).
                runCatching { cm.setCookie(url, "$name=; Max-Age=0; Path=/$suffix; Secure") }
                runCatching { cm.setCookie(url, "$name=; Max-Age=0; Path=/$suffix") }
            }
        }
        CookieFlusher.schedule()
        DebugLog.w(TAG, "낡은 챌린지 쿠키 삭제: $h → ${names.joinToString(", ")}")
    }

    /**
     * 진단용 — 그 호스트에 지금 실려 나가는 Cloudflare 쿠키 상태를 한 줄로.
     * `cf_clearance` 가 2개 이상이면 스코프 중복 오염이 확정된다.
     */
    fun describe(host: String?): String {
        val h = host?.lowercase()?.trim().orEmpty()
        if (h.isBlank()) return "(호스트 없음)"
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return "(쿠키 없음)"
        val all = namesOf(cm, "https://$h/")
        if (all.isEmpty()) return "쿠키 0개"
        val clearance = all.count { it.equals(CLEARANCE, ignoreCase = true) }
        val cf = all.filter { it.startsWith("cf", ignoreCase = true) || it.startsWith("__cf", ignoreCase = true) }
        return "쿠키 ${all.size}개, cf_clearance=$clearance" +
            (if (cf.isEmpty()) "" else ", cf계열=[${cf.joinToString(", ")}]") +
            fingerprint(cm, h)
    }

    /**
     * `cf_clearance` 값의 지문 (v1.3.67) — 라운드마다 **갱신되는지**를 본다.
     *
     * 갈림길이 여기다. 매 라운드 값이 **바뀌면** 챌린지는 실제로 풀려 새 토큰이
     * 발급되는데 서버가 곧바로 거부하는 것(= 환경 점수 문제)이고, 값이 **그대로면**
     * 애초에 새 토큰이 발급되지 않는 것(= 챌린지 제출 자체가 실패)이라 원인이
     * 완전히 다르다. 토큰 전체는 남기지 않고 앞 10자와 길이만 찍는다.
     */
    private fun fingerprint(cm: CookieManager, host: String): String {
        val raw = runCatching { cm.getCookie("https://$host/") }.getOrNull().orEmpty()
        val value = raw.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$CLEARANCE=", ignoreCase = true) }
            ?.substringAfter('=')
            .orEmpty()
        if (value.isBlank()) return ""
        return ", 토큰=${value.take(10)}…(len=${value.length})"
    }

    /** `getCookie`는 "a=1; b=2" 형태. 중복 스코프는 같은 이름이 여러 번 나온다. */
    private fun namesOf(cm: CookieManager, url: String): List<String> =
        runCatching { cm.getCookie(url) }.getOrNull().orEmpty()
            .split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotBlank() }
}
