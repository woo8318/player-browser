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
                hits.remove(key)?.let { DebugLog.d(TAG, "챌린지 사라짐(통과): $key") }
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
        if (count == 1 || count % 3 == 0) DebugLog.w(TAG, "  설정 상태: ${switchSnapshot()}")
        if (count >= 3) {
            DebugLog.e(TAG, "루프 의심: 같은 주소에서 캡차가 ${count}회 반복됨 — $key")
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
