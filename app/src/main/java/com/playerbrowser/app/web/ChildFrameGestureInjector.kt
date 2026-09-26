package com.playerbrowser.app.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.playerbrowser.app.network.ChallengeDetector
import com.playerbrowser.app.network.DebugLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 격리된(quarantined) 사이트의 **자식 프레임**에도 동영상 제스처 JS 를 싣는다 (v1.3.105).
 *
 * `video_gestures.js` 가 cross-origin iframe 에 들어가는 유일한 길은
 * [IframeScriptInjector] — iframe 문서를 자체 OkHttp 로 다시 받아 `<script>` 를
 * 끼워 넣는다. 그런데 [ChallengeDetector.isQuarantinedHost] 인 페이지는 그 재요청을
 * 통째로 건너뛴다(v1.3.56~88) — 그 호스트 요청이 우리 OkHttp(다른 TLS 지문)로 나가면
 * 방금 통과한 `cf_clearance` 가 즉시 무효가 되기 때문이다. 결과적으로 격리된
 * tvroom35 류 사이트에서는 **플레이어 iframe 에 제스처 스크립트가 한 번도 실린 적이
 * 없었다** — 그 안의 `<video>` 터치는 아무도 보지 않고, 최상위 프레임이 보내는
 * `__pbFs` postMessage 릴레이(`runFsGesture`/`broadcastFsGesture`)를 받을 리스너도
 * 없다. 그래서 격리 사이트에서만 ±10초 스와이프·더블탭이 죽은 것처럼 보였다.
 *
 * **네트워크는 건드리지 않는다.** [BrowserEnvPatch] 가 이미 같은 방식으로 모든
 * 프레임에 코드를 얹고 있다 — [WebViewCompat.addDocumentStartJavaScript] 는 iframe
 * 문서를 다시 받지 않고, WebView 자체 로더가 만든 문서에 **첫 스크립트보다 먼저**
 * 실행될 코드를 꽂을 뿐이다. 챌린지 루프를 만든 것은 "문서를 다시 받는 것"이었지
 * "그 문서에 스크립트가 있는 것"이 아니었다 — 방향이 다르다.
 *
 * **챌린지 페이지 규칙(잡지 말 것 — CLAUDE.md)을 그대로 지킨다:**
 *  - 최상위 프레임은 `window.top === window` 로 즉시 빠진다 — 그 프레임은
 *    `onPageFinished` 의 `!onChallenge` 블록이 `evaluateJavascript` 로 이미 처리한다.
 *    여기서 최상위 문서에 손대는 순간 챌린지 페이지 위에서 스크립트가 도는
 *    v1.3.87 루프가 되살아난다.
 *  - 자식 프레임도 [ChallengeDetector.CHALLENGE_HOSTS]/[ChallengeDetector.CHALLENGE_PATHS]
 *    (호스트 격리와 같은 출처)에 걸리거나 제목이 [ChallengeDetector.CHALLENGE_TITLE]
 *    이면 **아무 리스너도 걸기 전에** 빠진다.
 *
 * 타이밍은 `IframeScriptInjector`(문서 끝 `</body>` 직전)와 맞춰 `DOMContentLoaded`
 * 에 돈다. `window.__pbGestureInstalled` 를 그대로 봐서, `IframeScriptInjector` 가
 * 이미 같은 프레임에 스크립트를 심었으면(격리 안 된 사이트) 이 주입은 조용히
 * no-op 이다 — 두 경로가 같은 프레임에서 동시에 등록되어도 안전하다.
 *
 * 스트림 내용 감지(`initBodySniff`, v1.3.91)는 이 경로로 실린 프레임에서 **항상
 * 끈다**(`__pbBodySniffOff`). 브리지의 `bodySniffEnabled()` 는 **최상위 페이지
 * 호스트**(`PlayerBridge.pageHost`)만 보므로, 격리되지 않은 페이지에 박힌 **격리
 * 호스트(계열) iframe** — `IframeScriptInjector` 가 건너뛴 바로 그 프레임 — 에서도
 * 참을 돌려줘 fetch/XHR 를 감싸게 된다(격리 사이트에서 fetch 를 감싸는 것은
 * v1.3.91 이 이미 금지한 짓이다). 이 경로가 실제로 스크립트를 싣는 프레임은 전부
 * 예전엔 스크립트가 아예 없던 프레임(격리 페이지·격리 호스트 iframe·재요청 실패)
 * 이라 끄더라도 잃는 것이 없다.
 */
object ChildFrameGestureInjector {

    private const val TAG = "FrameGesture"
    private val logged = AtomicBoolean(false)

    fun install(view: WebView, gestureScript: String) {
        if (gestureScript.isBlank()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            if (logged.compareAndSet(false, true)) {
                DebugLog.w(TAG, "document-start 미지원 — 격리 페이지의 플레이어 iframe 엔 제스처 JS 없음")
            }
            return
        }
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(view, buildWrapper(gestureScript), setOf("*"))
        }.onSuccess {
            if (logged.compareAndSet(false, true)) {
                DebugLog.d(TAG, "자식 프레임 제스처 주입 등록 (document-start, 네트워크 무관)")
            }
        }.onFailure { e ->
            DebugLog.w(TAG, "자식 프레임 제스처 주입 등록 실패(무시): ${e.javaClass.simpleName}")
        }
    }

    /**
     * `ChallengeDetector` 목록을 JSON 리터럴로 그대로 미러링해 JS 배열/정규식으로
     * 박아 넣는다 — 두 번째 판별기를 손으로 다시 타이핑하지 않는다(단일 출처).
     */
    internal fun buildWrapper(gestureScript: String): String {
        val hostsJson = JSONArray(ChallengeDetector.CHALLENGE_HOSTS.toList()).toString()
        val pathsJson = JSONArray(ChallengeDetector.CHALLENGE_PATHS).toString()
        val titleJson = JSONObject.quote(ChallengeDetector.CHALLENGE_TITLE.pattern)
        return buildString {
            append(
                """
(function () {
  try {
    if (window.top === window) return;
    var loc = window.location;
    if (loc.protocol !== 'http:' && loc.protocol !== 'https:') return;
    var host = String(loc.hostname || '').toLowerCase();
    var hosts = """.trimStart('\n')
            )
            append(hostsJson)
            append(";\n")
            append(
                """    for (var i = 0; i < hosts.length; i++) {
      var h = hosts[i];
      if (host === h || (host.length > h.length && host.slice(-h.length - 1) === '.' + h)) return;
    }
    var path = String(loc.pathname || '').toLowerCase();
    var paths = """
            )
            append(pathsJson)
            append(";\n")
            append(
                """    for (var j = 0; j < paths.length; j++) {
      if (path.indexOf(paths[j]) !== -1) return;
    }
  } catch (e) { return; }
  var titleRe;
  try { titleRe = new RegExp("""
            )
            append(titleJson)
            append(
                """, 'i'); } catch (e) { return; }
  function run() {
    try {
      if (window.__pbGestureInstalled) return;
      if (titleRe.test(String(document.title || ''))) return;
      // 브리지의 bodySniffEnabled() 는 최상위 페이지 호스트만 본다 — 이 프레임
      // 자신이 격리 호스트여도 참일 수 있다. 여기선 fetch/XHR 래핑을 항상 끈다
      // (v1.3.91 규칙 유지 — 이 경로 전엔 이 프레임들에 스크립트가 없었다).
      window.__pbBodySniffOff = true;
    } catch (e) { return; }
    try {
"""
            )
            append(gestureScript)
            if (!gestureScript.endsWith("\n")) append('\n')
            append(
                """    } catch (e) {}
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', run, { once: true });
  } else {
    run();
  }
})();
"""
            )
        }
    }
}
