package com.playerbrowser.app.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.playerbrowser.app.network.DebugLog

/**
 * Android WebView의 JS 환경을 **Chrome for Android와 같게 보이도록 정규화**한다 (v1.3.60).
 *
 * v1.3.58(UA 문자열) + v1.3.59(`Sec-CH-UA` 브랜드)로 헤더 수준의 WebView 표식은
 * 전부 지웠는데도 Cloudflare 비대화형 챌린지가 그대로 반복됐다. 헤더가 아니라
 * **자바스크립트로 읽히는 환경**이 여전히 WebView라고 말하고 있기 때문이다.
 * 진짜 Chrome for Android엔 있고 WebView엔 없는 대표적인 두 가지:
 *
 *  - `window.chrome` — Chrome이 노출하는 `loadTimes()`/`csi()`. WebView엔 없다.
 *    헤드리스/자동화 판별의 고전적인 지표라 안티봇이 거의 반드시 본다.
 *  - `Notification` — Chrome Android엔 있고 WebView엔 아예 정의돼 있지 않다.
 *
 * **주입 방식이 중요하다.** v1.3.54~57에서 루프를 만든 "챌린지 페이지 개입"은
 * `shouldInterceptRequest`로 **문서를 다시 받아** CSP를 벗기고 본문을 재인코딩하던
 * 것이었다 — 네트워크 경로가 갈라지는 게 원인이었다. 여기서 쓰는
 * [WebViewCompat.addDocumentStartJavaScript]는 네트워크를 전혀 건드리지 않고
 * WebView 자체 로더가 만든 문서에 **첫 스크립트보다 먼저** 실행될 코드를 얹는다.
 * 방향도 반대다 — 신호를 더하는 게 아니라 지운다.
 *
 * 모든 오리진에 건다. 챌린지 페이지에만 걸면 "그 페이지에서만 환경이 달라지는"
 * 것 자체가 또 하나의 신호가 되고, 안티봇은 챌린지 이전 페이지에서도 지문을
 * 수집한다. 기기가 이 API를 지원하지 않으면 조용히 넘어간다.
 *
 * **한계:** `PaymentRequest` 부재 등 남는 표식이 더 있고, 셰이프를 어설프게
 * 흉내 내면 그 자체가 신호가 될 수 있어 널리 검증된 두 가지만 손댄다.
 */
object BrowserEnvPatch {

    private const val TAG = "UserAgent"

    fun install(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            DebugLog.w(TAG, "document-start 주입 미지원 기기 — JS 환경 정규화 생략")
            return
        }
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf("*"))
            DebugLog.d(TAG, "JS 환경 정규화 주입: window.chrome + Notification")
        }.onFailure {
            DebugLog.w(TAG, "JS 환경 정규화 실패(무시): ${it.javaClass.simpleName}")
        }
    }

    @Volatile private var probed = false

    /**
     * **어떤 표식이 남아 있는지 기기에서 직접 찍어본다 (v1.3.61).**
     *
     * WebView에 무엇이 없는지는 기기/WebView 버전마다 다르고, 목록을 추측으로
     * 채우면 어설픈 셰이프가 오히려 신호가 된다. 그래서 진짜 Chrome for Android엔
     * 있는 API들을 한 번 훑어 **없는 것만** 로그에 남긴다 — 다음 라운드에서 무엇을
     * 채울지 로그가 직접 알려주게 하는 것이 목적. 앱 실행당 1회, 챌린지가 아닌
     * 페이지에서만 돈다.
     */
    fun probeEnvironment(view: WebView) {
        if (probed) return
        probed = true
        runCatching {
            view.evaluateJavascript(PROBE_JS) { raw ->
                val out = raw?.trim()?.removeSurrounding("\"").orEmpty()
                    .replace("\\\"", "\"").replace("\\\\", "\\")
                if (out.isNotBlank()) DebugLog.w(TAG, "JS 환경 진단 — 없는 것: $out")
                else DebugLog.d(TAG, "JS 환경 진단: 확인한 항목 모두 존재")
            }
        }
    }

    /** Chrome for Android엔 있는 API 목록. 없는 것만 이름을 모아 돌려준다. */
    private val PROBE_JS = """
(function () {
  try {
    var miss = [];
    var has = function (label, get) {
      try { if (typeof get() === 'undefined' || get() === null) miss.push(label); }
      catch (e) { miss.push(label + '!'); }
    };
    has('window.chrome', function () { return window.chrome; });
    has('chrome.loadTimes', function () { return window.chrome && window.chrome.loadTimes; });
    has('chrome.csi', function () { return window.chrome && window.chrome.csi; });
    has('Notification', function () { return window.Notification; });
    has('PaymentRequest', function () { return window.PaymentRequest; });
    has('PublicKeyCredential', function () { return window.PublicKeyCredential; });
    has('navigator.credentials', function () { return navigator.credentials; });
    has('navigator.serviceWorker', function () { return navigator.serviceWorker; });
    has('navigator.permissions', function () { return navigator.permissions; });
    has('navigator.mediaSession', function () { return navigator.mediaSession; });
    has('navigator.share', function () { return navigator.share; });
    has('navigator.bluetooth', function () { return navigator.bluetooth; });
    has('navigator.usb', function () { return navigator.usb; });
    has('navigator.wakeLock', function () { return navigator.wakeLock; });
    has('navigator.storage', function () { return navigator.storage; });
    has('navigator.presentation', function () { return navigator.presentation; });
    has('navigator.getInstalledRelatedApps', function () { return navigator.getInstalledRelatedApps; });
    has('navigator.virtualKeyboard', function () { return navigator.virtualKeyboard; });
    has('navigator.userActivation', function () { return navigator.userActivation; });
    has('speechSynthesis', function () { return window.speechSynthesis; });
    has('BeforeInstallPromptEvent', function () { return window.BeforeInstallPromptEvent; });
    has('BatteryManager', function () { return window.BatteryManager; });
    has('navigator.getBattery', function () { return navigator.getBattery; });
    has('trustedTypes', function () { return window.trustedTypes; });
    has('navigator.userAgentData', function () { return navigator.userAgentData; });

    var extra = [];
    try { extra.push('plugins=' + navigator.plugins.length); } catch (e) {}
    try { extra.push('webdriver=' + navigator.webdriver); } catch (e) {}
    try {
      if (navigator.userAgentData && navigator.userAgentData.brands) {
        extra.push('brands=' + navigator.userAgentData.brands.map(function (b) {
          return b.brand + '/' + b.version;
        }).join('|'));
      }
    } catch (e) {}
    try {
      var pb = [];
      for (var k in window) { if (k.indexOf('PB') === 0) { pb.push(k); } }
      if (pb.length) { extra.push('브리지=' + pb.join(',')); }
    } catch (e) {}
    return miss.join(', ') + (extra.length ? '  [' + extra.join(' ') + ']' : '');
  } catch (e) { return 'probe error: ' + e; }
})();
""".trimIndent()

    /**
     * 모든 프레임에서 페이지 스크립트보다 먼저 실행된다. **이미 존재하면 손대지
     * 않는다** — 진짜로 값이 있는 환경을 우리가 덮어써 오히려 어긋나게 만들지
     * 않기 위해서다. 전체를 try/catch로 감싸 어떤 실패도 페이지에 영향을 주지 않는다.
     */
    private val SCRIPT = """
(function () {
  'use strict';
  var nativeLike = function (fn, name) {
    try {
      Object.defineProperty(fn, 'name', { value: name, configurable: true });
      Object.defineProperty(fn, 'toString', {
        value: function toString() { return 'function ' + name + '() { [native code] }'; },
        configurable: true, writable: true
      });
    } catch (e) {}
    return fn;
  };

  try {
    if (!window.chrome) {
      var t0 = Date.now() / 1000;
      var shim = {};
      shim.loadTimes = nativeLike(function () {
        return {
          requestTime: t0, startLoadTime: t0, commitLoadTime: t0,
          finishDocumentLoadTime: t0, finishLoadTime: t0,
          firstPaintTime: t0, firstPaintAfterLoadTime: 0,
          navigationType: 'Other', wasFetchedViaSpdy: true,
          wasNpnNegotiated: true, npnNegotiatedProtocol: 'h2',
          wasAlternateProtocolAvailable: false, connectionInfo: 'h2'
        };
      }, 'loadTimes');
      shim.csi = nativeLike(function () {
        var now = Date.now();
        return { startE: now, onloadT: now, pageT: 1, tran: 15 };
      }, 'csi');
      Object.defineProperty(window, 'chrome', {
        value: shim, configurable: true, writable: true, enumerable: true
      });
    }
  } catch (e) {}

  try {
    if (typeof window.Notification === 'undefined') {
      var N = function Notification(title, options) {
        if (!(this instanceof N)) {
          throw new TypeError("Failed to construct 'Notification': Please use the 'new' operator.");
        }
        if (arguments.length < 1) {
          throw new TypeError("Failed to construct 'Notification': 1 argument required.");
        }
        var o = options || {};
        this.title = String(title);
        this.body = o.body === undefined ? '' : String(o.body);
        this.icon = o.icon === undefined ? '' : String(o.icon);
        this.tag = o.tag === undefined ? '' : String(o.tag);
        this.lang = o.lang === undefined ? '' : String(o.lang);
        this.dir = o.dir === undefined ? 'auto' : String(o.dir);
        this.data = o.data === undefined ? null : o.data;
        this.silent = o.silent === undefined ? null : !!o.silent;
        this.onclick = null; this.onshow = null; this.onerror = null; this.onclose = null;
      };
      nativeLike(N, 'Notification');
      N.prototype.close = nativeLike(function () {}, 'close');
      N.prototype.addEventListener = nativeLike(function () {}, 'addEventListener');
      N.prototype.removeEventListener = nativeLike(function () {}, 'removeEventListener');
      N.prototype.dispatchEvent = nativeLike(function () { return false; }, 'dispatchEvent');
      N.requestPermission = nativeLike(function (cb) {
        var p = Promise.resolve('default');
        if (typeof cb === 'function') { p.then(cb); }
        return p;
      }, 'requestPermission');
      Object.defineProperty(N, 'permission', {
        get: function () { return 'default'; }, configurable: true, enumerable: false
      });
      Object.defineProperty(N, 'maxActions', {
        get: function () { return 2; }, configurable: true, enumerable: false
      });
      Object.defineProperty(window, 'Notification', {
        value: N, configurable: true, writable: true, enumerable: false
      });
    }
  } catch (e) {}
})();
""".trimIndent()
}
