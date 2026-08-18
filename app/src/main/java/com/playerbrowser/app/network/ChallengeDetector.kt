package com.playerbrowser.app.network

import android.net.Uri
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 안티봇 "사람인지 확인"(캡차 / 챌린지) 흐름 보호 + 진단.
 *
 * 두 가지 역할:
 *
 *  1. **가로채기 금지 목록** — Cloudflare Turnstile / hCaptcha / reCAPTCHA 같은
 *     챌린지 요청은 우리가 절대 건드리면 안 된다. [IframeScriptInjector]가
 *     챌린지 iframe 문서를 자체 OkHttp로 다시 받아 CSP를 벗기고 스크립트를
 *     끼워 넣거나, [SniBypassClient]가 챌린지 GET만 가로채고 검증 POST는
 *     (본문을 볼 수 없어) 네이티브로 흘려보내면 커넥션/쿠키 컨텍스트가
 *     어긋나 챌린지가 영원히 완료되지 않는다 — 체크박스를 눌러도 계속
 *     "사람인지 확인" 화면만 다시 뜨는 증상. 이 목록에 걸리는 요청은
 *     통째로 WebView 네이티브 로더에 맡긴다.
 *
 *  2. **진단 로그** — 어떤 사이트가 어떤 캡차를 쓰는지 사용자가 알기 어려우므로,
 *     페이지가 로드될 때마다 [PROBE_JS]로 DOM에 챌린지 위젯이 있는지 살펴
 *     디버그 로그에 남긴다. 같은 주소에서 반복 감지되면 "루프 의심"으로
 *     승격하고 그 순간의 토글 상태를 함께 찍어 원인 추적을 돕는다.
 */
object ChallengeDetector {

    private const val TAG = "Captcha"

    /** 호스트 suffix 매칭 (host == entry || host endsWith ".entry"). */
    private val CHALLENGE_HOSTS: Set<String> = setOf(
        "challenges.cloudflare.com",
        "hcaptcha.com",
        "recaptcha.net",
        "arkoselabs.com",
        "funcaptcha.com",
        "geetest.com",
        "datadome.co",
        "perimeterx.net",
        "px-cloud.net",
        "px-cdn.net",
        "incapsula.com",
        "imperva.com"
    )

    /**
     * 경로 substring. 호스트로 못 거르는 것들 — reCAPTCHA는 google.com /
     * gstatic.com에서 오고(호스트 전체를 뺄 순 없다), Cloudflare 챌린지는
     * 사이트 자기 도메인의 `/cdn-cgi/` 아래에서 돈다.
     */
    private val CHALLENGE_PATHS: List<String> = listOf(
        // Cloudflare 챌린지 전용 경로만 — `/cdn-cgi/` 전체를 빼면 이미지 리사이징
        // (`/cdn-cgi/image/`)까지 네이티브로 흘러 차단 사이트 이미지가 다시 깨진다.
        "/cdn-cgi/challenge-platform/",
        "/cdn-cgi/managed/",
        "/cdn-cgi/bm/",
        "/cdn-cgi/l/chk_jschl",
        "/recaptcha/",
        "/turnstile/",
        "/_incapsula_resource",
        "/_sec/cp_challenge",
        "/captcha/"
    )

    fun isChallengeRequest(url: Uri?): Boolean {
        if (url == null) return false
        val host = url.host?.lowercase()
        if (host != null && CHALLENGE_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val path = url.encodedPath?.lowercase() ?: return false
        return CHALLENGE_PATHS.any { path.contains(it) }
    }

    // ---------------------------------------------------------------------
    // 챌린지 호스트 격리 (v1.3.56)
    //
    // 위 목록은 "챌린지 요청"만 걸러낸다. 그런데 Cloudflare 챌린지 페이지 자체는
    // 사이트 루트(`https://example.com/`)가 403으로 내려주는 **메인 프레임 문서**라
    // 호스트/경로 어느 쪽에도 안 걸려 여전히 우리 OkHttp가 가져간다. 반면 챌린지를
    // 실제로 푸는 검증 POST는 `shouldInterceptRequest`가 본문을 못 봐서 **영원히**
    // 네이티브 로더로 나간다. 결과적으로
    //
    //   * 챌린지를 푸는 건 네이티브(Chromium TLS 지문)
    //   * 통과 후 문서를 다시 받는 건 우리 OkHttp(OkHttp TLS 지문 + Chrome UA)
    //
    // 로 갈라지는데, Cloudflare가 발급하는 `cf_clearance`는 IP + UA + TLS 지문에
    // 묶여 있어 지문이 어긋나면 무효 처리 → 또 챌린지 → 무한 루프가 된다(같은
    // 쿠키가 서로 다른 지문에서 오는 것 자체가 강한 봇 신호이기도 하다).
    //
    // POST를 가로챌 방법이 없는 이상 일관성을 얻는 유일한 길은 **그 호스트를
    // 통째로 네이티브에 맡기는 것**이다. 챌린지 응답/위젯을 한 번이라도 본
    // 호스트는 여기 격리해 두고, [SniBypassClient]와 [IframeScriptInjector]가
    // 그 호스트의 모든 요청에서 손을 뗀다.
    // ---------------------------------------------------------------------

    /** 격리 TTL — 사실상 세션 내내. 챌린지를 쓰는 사이트는 계속 쓰기 때문. */
    private const val QUARANTINE_MS = 6L * 60L * 60L * 1000L

    /** host -> 격리 만료 시각(ms). */
    private val quarantined = ConcurrentHashMap<String, Long>()

    /**
     * 이 메인 프레임 응답이 Cloudflare 챌린지 페이지인가?
     * 챌린지는 403(managed challenge) 또는 503(legacy JS challenge)으로 내려오고
     * Cloudflare가 `cf-mitigated: challenge` 헤더를 붙인다. 구버전 대비로
     * `cf-ray`(Cloudflare 경유 표식)도 함께 인정한다.
     */
    fun isChallengeResponse(code: Int, header: (String) -> String?): Boolean {
        if (code != 403 && code != 503 && code != 429) return false
        if (!header("cf-mitigated").isNullOrBlank()) return true
        return !header("cf-ray").isNullOrBlank()
    }

    fun markChallengedHost(host: String?, why: String) {
        val h = host?.lowercase()?.trim().orEmpty()
        if (h.isBlank()) return
        val now = System.currentTimeMillis()
        markChallengeActive(h)
        val prev = quarantined.put(h, now + QUARANTINE_MS)
        // 만료분만 걷어낸다 — 진단용 맵과 달리 통째로 비우면 루프가 되살아난다.
        if (quarantined.size > 64) {
            quarantined.entries.removeAll { it.value <= now }
        }
        if (prev == null || prev < now) {
            DebugLog.w(TAG, "챌린지 호스트 → 네이티브 전담: $h ($why)")
            DebugLog.w(TAG, "  설정 상태: ${switchSnapshot()}")
            // 우리가 예전에 심어놓은 낡은 통과 쿠키가 남아 있으면 새로 받은
            // 토큰과 함께 나가 Cloudflare가 거부한다 — 깨끗이 지우고 시작.
            ChallengeCookies.reset(h)
            lastCookieReset[h] = now
        }
    }

    /**
     * 격리했는데도 루프가 계속되면(=통과 토큰이 매번 거부됨) 쿠키 오염이
     * 남아 있는 것이므로 한 번 더 정리한다. 진행 중인 챌린지를 반복해서
     * 끊지 않도록 호스트당 60초에 한 번으로 제한.
     */
    private fun resetCookiesIfLooping(host: String?) {
        val h = host?.lowercase()?.trim().orEmpty()
        if (h.isBlank()) return
        val now = System.currentTimeMillis()
        val last = lastCookieReset[h]
        if (last != null && now - last < 60_000L) return
        lastCookieReset[h] = now
        ChallengeCookies.reset(h)
    }

    private val lastCookieReset = ConcurrentHashMap<String, Long>()

    // ---------------------------------------------------------------------
    // "지금 챌린지가 떠 있는가" — 격리(6시간)와는 별개의 짧은 창.
    //
    // 네트워크 격리는 통과 후에도 유지해야 한다(통과 토큰은 그 흐름을 처리한
    // TLS 지문에 묶여 있다). 하지만 페이지 쪽 기능 — 제스처 스크립트 주입,
    // 광고차단 CSS, 쿠키배너 킬러, JS 브리지 — 까지 6시간 끄면 정작 그
    // 사이트에서 동영상 제스처도 이어보기도 못 쓴다. 그래서 이것들은
    // **챌린지 화면이 실제로 떠 있는 동안만** 접어둔다.
    // ---------------------------------------------------------------------

    private const val ACTIVE_MS = 60_000L
    private val challengeActive = ConcurrentHashMap<String, Long>()

    /** 챌린지 화면이 방금(60초 내) 감지된 호스트인가. */
    fun isChallengeActive(host: String?): Boolean {
        val h = host?.lowercase()?.trim().orEmpty()
        if (h.isBlank()) return false
        val until = challengeActive[h] ?: return false
        if (System.currentTimeMillis() >= until) {
            challengeActive.remove(h)
            return false
        }
        return true
    }

    private fun markChallengeActive(host: String?) {
        val h = host?.lowercase()?.trim().orEmpty()
        if (h.isBlank()) return
        challengeActive[h] = System.currentTimeMillis() + ACTIVE_MS
        prune(challengeActive)
    }

    fun isQuarantinedHost(host: String?): Boolean {
        val h = host?.lowercase()?.trim().orEmpty()
        if (h.isBlank()) return false
        val until = quarantined[h] ?: return false
        if (System.currentTimeMillis() >= until) {
            quarantined.remove(h)
            return false
        }
        return true
    }

    /**
     * 격리된 호스트가 **접속 자체에 실패**하면(= DPI 차단) 격리를 푼다.
     * 네이티브 경로는 ClientHello 단편화가 없어 차단 사이트에선 아예 못 뚫는데,
     * 그대로 두면 캡차 대신 접속 실패로 바뀔 뿐이라 다음 시도는 SNI 우회
     * 경로로 되돌린다(캡차 루프 < 접속 불가).
     */
    fun clearQuarantine(host: String?) {
        val h = host?.lowercase()?.trim().orEmpty()
        if (h.isBlank()) return
        if (quarantined.remove(h) != null) {
            DebugLog.w(TAG, "네이티브 전담 해제(접속 실패) → 우회 경로 복귀: $h")
        }
    }

    /**
     * 우리가 가로채기를 포기한 챌린지 요청을 로그로 남긴다. 챌린지는 요청이
     * 수십 개씩 쏟아지므로 (호스트, 종류)당 30초에 한 번만 기록.
     */
    fun noteSkipped(kind: String, url: Uri) {
        val host = url.host?.lowercase().orEmpty()
        val key = "$kind|$host"
        val now = System.currentTimeMillis()
        val last = skipLog[key]
        if (last != null && now - last < 30_000L) return
        prune(skipLog)
        skipLog[key] = now
        DebugLog.d(TAG, "챌린지 요청 통과($kind, 가로채기 안 함): $host${url.encodedPath.orEmpty()}")
    }

    /**
     * 페이지 로드 완료 시 호출 — DOM에서 캡차 위젯을 찾아 로그로 남긴다.
     * 챌린지 위젯은 `onPageFinished` 뒤에 스크립트로 그려지는 경우가 많아
     * 즉시 한 번 + 2.5초 뒤 한 번, 같은 토큰으로 두 번 살핀다(중복 로그는 제거).
     */
    fun probe(view: WebView?, url: String?) {
        if (view == null || url.isNullOrBlank()) return
        if (!url.startsWith("http")) return
        val token = pageToken.incrementAndGet()
        runProbe(view, url, token, final = false)
        runCatching {
            view.postDelayed({ runProbe(view, url, token, final = true) }, 2500L)
        }
    }

    private fun runProbe(view: WebView, url: String, token: Int, final: Boolean) {
        // 탭이 닫혀 WebView가 destroy됐으면 throw — 지연 프로브라 정상적인 경우다.
        runCatching {
            view.evaluateJavascript(PROBE_JS) { raw -> report(url, decode(raw), token, final) }
        }
    }

    private fun report(url: String, markers: String, token: Int, final: Boolean) {
        val key = normalize(url)
        if (markers.isBlank()) {
            // 지연 프로브까지 아무것도 없으면 이 페이지엔 캡차가 없다 = 통과.
            // 즉시 프로브의 공백은 "아직 안 그려짐"일 수 있어 판단하지 않는다.
            if (final && !reported.containsKey(token)) {
                hits.remove(key)?.let {
                    DebugLog.d(TAG, "챌린지 사라짐(통과): $key")
                    // 네트워크 격리는 유지(통과 토큰이 네이티브 지문에 묶임),
                    // 페이지 기능만 곧바로 되살린다.
                    runCatching { Uri.parse(url).host }.getOrNull()
                        ?.lowercase()?.let { h -> challengeActive.remove(h) }
                }
            }
            return
        }
        prune(reported)
        val prev = reported.put(token, markers)
        if (prev == markers) return
        if (prev != null) {
            DebugLog.d(TAG, "  위젯 변화: $markers")
            return
        }
        prune(hits)
        val count = (hits[key] ?: 0) + 1
        hits[key] = count
        DebugLog.w(TAG, "감지 #$count: $key → $markers")
        // 응답 헤더로 못 잡은 챌린지(200으로 내려오는 인터스티셜 등)도 격리한다.
        // 단 **가로막는 인터스티셜일 때만** — 로그인 폼에 얹힌 reCAPTCHA 위젯까지
        // 격리하면 멀쩡한 사이트가 SNI 우회 경로를 잃는다. 판별은 "Just a moment"
        // 류 제목(=문서 전체가 챌린지) 또는 Cloudflare 챌린지 폼 존재로 한정.
        if (markers.contains("title=") || markers.contains("cf-challenge")) {
            markChallengedHost(runCatching { Uri.parse(url).host }.getOrNull(), "DOM 인터스티셜")
        }
        if (count == 1 || count % 3 == 0) DebugLog.w(TAG, "  설정 상태: ${switchSnapshot()}")
        if (count >= 3) {
            DebugLog.e(TAG, "루프 의심: 같은 주소에서 캡차가 ${count}회 반복됨 — $key")
            resetCookiesIfLooping(runCatching { Uri.parse(url).host }.getOrNull())
        }
    }

    private fun switchSnapshot(): String =
        "SNI우회=${SniBypassSwitch.enabled}, 프라이빗DNS=${PrivateDnsSwitch.enabled}, " +
            "광고차단=${AdBlockSwitch.enabled}, 쿠키배너제거=${CookieBannerSwitch.enabled}, " +
            "링크새탭=${LinkNewTabSwitch.enabled}"

    /** 쿼리/프래그먼트를 뗀 주소 — 챌린지 토큰(`?__cf_chl_rt_tk=`)이 매번 달라 반복을 못 세는 걸 방지. */
    private fun normalize(url: String): String = runCatching {
        val u = Uri.parse(url)
        "${u.host.orEmpty()}${u.encodedPath.orEmpty()}"
    }.getOrDefault(url).ifBlank { url }

    /** evaluateJavascript 결과는 JSON 리터럴("...")로 온다. */
    private fun decode(raw: String?): String {
        if (raw == null || raw == "null" || raw == "\"\"") return ""
        val body = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw.substring(1, raw.length - 1)
        } else raw
        return body.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\").trim()
    }

    /** 주소(host+path)별 캡차 감지 누적 횟수 — 루프 판정용. */
    private val hits = ConcurrentHashMap<String, Int>()
    /** 페이지 로드 1회당 하나의 토큰 — 즉시/지연 프로브의 중복 로그를 막는다. */
    private val reported = ConcurrentHashMap<Int, String>()
    private val pageToken = AtomicInteger()
    private val skipLog = ConcurrentHashMap<String, Long>()

    /** 오래 돌아도 맵이 무한히 자라지 않게 — 넘치면 통째로 비운다(진단용이라 손실 무해). */
    private fun prune(map: ConcurrentHashMap<*, *>) {
        if (map.size > 64) map.clear()
    }

    /**
     * 알려진 캡차 위젯을 찾아 사람이 읽을 수 있는 요약을 돌려주는 JS.
     * 아무것도 없으면 빈 문자열. `(hidden!)` 접미사는 위젯이 존재하지만
     * CSS로 숨겨졌다는 뜻 — 광고차단/쿠키배너 CSS가 캡차를 가려버린 경우를
     * 잡아내기 위한 표시.
     */
    val PROBE_JS: String = """
        (function () {
          try {
            var out = [];
            var t = document.title || '';
            if (/just a moment|checking your browser|attention required|verify you are human|사람인지|잠시.{0,3}기다|확인 중/i.test(t)) {
              out.push('title="' + t.slice(0, 40) + '"');
            }
            var sel = {
              turnstile: '.cf-turnstile,#cf-turnstile,[data-sitekey][class*=turnstile]',
              'cf-challenge': '#challenge-form,#challenge-running,#cf-challenge-running,#challenge-stage,#cf-please-wait',
              recaptcha: '.g-recaptcha,#g-recaptcha,iframe[src*="/recaptcha/"]',
              hcaptcha: '.h-captcha,iframe[src*="hcaptcha.com"]',
              arkose: 'iframe[src*="arkoselabs"],iframe[src*="funcaptcha"]',
              geetest: '.geetest_holder,iframe[src*="geetest"]',
              datadome: '#ddv1-captcha-container,iframe[src*="datadome"]',
              incapsula: 'iframe[src*="_Incapsula_Resource"]'
            };
            for (var k in sel) {
              try {
                var el = document.querySelector(sel[k]);
                if (!el) continue;
                var hidden = false;
                try {
                  var cs = getComputedStyle(el);
                  hidden = cs.display === 'none' || cs.visibility === 'hidden';
                } catch (e1) {}
                out.push(k + (hidden ? '(hidden!)' : ''));
              } catch (e2) {}
            }
            try {
              var fr = [], ifr = document.querySelectorAll('iframe');
              for (var i = 0; i < ifr.length && fr.length < 3; i++) {
                var s = ifr[i].src || '';
                if (/challenges\.cloudflare\.com|hcaptcha|\/recaptcha\/|arkoselabs|geetest|datadome/i.test(s)) {
                  fr.push(s.slice(0, 90));
                }
              }
              if (fr.length) out.push('iframe=' + fr.join(' | '));
            } catch (e3) {}
            return out.length ? out.join(', ') : '';
          } catch (e) {
            return '';
          }
        })();
    """.trimIndent()
}
