package com.playerbrowser.app.network

import android.webkit.CookieManager

/**
 * 캡차 루프의 마지막 조각 — **우리가 심어놓은 낡은 Cloudflare 쿠키**를 지운다.
 *
 * v1.3.54~55에서 [SniBypassClient] / [com.playerbrowser.app.web.IframeScriptInjector]는
 * 가로챈 응답의 `Set-Cookie`를 헤더에서 떼어 [CookieManager.setCookie]로 직접
 * 심고 [CookieFlusher]로 디스크에 영속화했다. 그런데 그렇게 심은 쿠키는
 * 도메인 스코프가 네이티브 로더가 심는 것과 어긋날 수 있어(`example.com` vs
 * `.example.com`) **같은 이름의 `cf_clearance`가 두 벌** 남는다. 그러면 이후
 * 모든 요청에 통과 토큰이 두 개 실려 Cloudflare가 거부하고, 캡차를 몇 번을
 * 풀어도 새 토큰이 낡은 토큰과 함께 나가며 영원히 다시 챌린지가 뜬다.
 * (다른 브라우저는 이런 오염이 없으니 같은 사이트가 잘 열린다.)
 *
 * 그래서 챌린지를 감지해 호스트를 격리하는 순간, 그 호스트의 Cloudflare
 * 챌린지 관련 쿠키를 통째로 만료시켜 **깨끗한 상태에서 다시 시작**하게 한다.
 * 지워도 손해가 없다 — 어차피 챌린지가 떠 있다는 건 지금 있는 토큰이 통하지
 * 않는다는 뜻이고, 통과하면 곧바로 새로 발급된다. 로그인 세션 쿠키 등
 * 사이트 자체 쿠키는 건드리지 않는다.
 */
object ChallengeCookies {

    /** 정확히 일치시킬 이름 + `cf_chl` 접두사(챌린지 진행 상태 쿠키들). */
    private val NAMES = listOf("cf_clearance", "__cf_bm", "__cfruid", "__cfwaitingroom")

    private fun isChallengeCookie(name: String): Boolean =
        NAMES.any { name.equals(it, ignoreCase = true) } ||
            name.startsWith("cf_chl", ignoreCase = true)

    fun reset(host: String?) {
        val h = host?.lowercase()?.trim().orEmpty()
        if (h.isBlank()) return
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        val url = "https://$h/"

        val names = runCatching { cm.getCookie(url) }.getOrNull().orEmpty()
            .split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotBlank() && isChallengeCookie(it) }
            .distinct()
        if (names.isEmpty()) return

        // 도메인 스코프를 모르므로 있을 법한 조합을 모두 만료시킨다 — 정확한
        // 스코프로 만료시키지 않으면 그 쿠키만 살아남아 오염이 그대로 남는다.
        val bare = h.removePrefix("www.")
        val domains = linkedSetOf("", h, ".$h", bare, ".$bare")
        for (name in names) {
            for (d in domains) {
                val suffix = if (d.isBlank()) "" else "; Domain=$d"
                runCatching { cm.setCookie(url, "$name=; Max-Age=0; Path=/$suffix") }
            }
        }
        CookieFlusher.schedule()
        DebugLog.w("Captcha", "낡은 챌린지 쿠키 삭제: $h → ${names.joinToString(", ")}")
    }
}
