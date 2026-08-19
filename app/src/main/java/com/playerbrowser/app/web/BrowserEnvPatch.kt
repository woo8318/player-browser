package com.playerbrowser.app.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.playerbrowser.app.network.DebugLog
import com.playerbrowser.app.network.EnvSpoofSwitch

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
 * **v1.3.62 — 추측 대신 진단 결과로 채운다.** v1.3.61의 [probeEnvironment]가 이
 * 기기(SM-F776N / WebView Chrome 150)에서 실제로 없는 API 이름을 로그에 찍었다:
 * `PaymentRequest`, `PublicKeyCredential`, `navigator.mediaSession`,
 * `navigator.share`, `navigator.bluetooth`, `navigator.usb`,
 * `navigator.presentation`, `navigator.getInstalledRelatedApps`,
 * `speechSynthesis` — 아홉 개 전부 Chrome for Android엔 있다. 하나만 없어도
 * WebView로 특정되므로 골라 채우는 건 의미가 없어 한 번에 전부 정의한다.
 *
 * **부작용을 안 만드는 방식으로 채운다:** 존재는 하되 capability 질의
 * (`canShare` / `canMakePayment` / `getAvailability` /
 * `isUserVerifyingPlatformAuthenticatorAvailable`)는 전부 false를 돌려준다.
 * 그래서 사이트가 "공유"·"결제"·"생체인증" 버튼을 새로 그렸다가 눌러도 아무
 * 일이 없는 상황이 생기지 않고, 실제로 못 쓰는 기능은 종전처럼 조용히 숨겨진다.
 * `navigator.*`는 인스턴스가 아니라 `Navigator.prototype`에 정의해
 * `Object.getOwnPropertyNames(Navigator.prototype)` 수준의 검사와도 어긋나지 않는다.
 *
 * **v1.3.63 — shim이 가짜라는 게 그대로 보이던 구멍을 막는다.** v1.3.62는 각
 * shim 함수에 **자체 `toString`**을 심었는데, 안티봇이 실제로 쓰는 검사는
 * `Function.prototype.toString.call(fn)` 형태라 자체 프로퍼티를 건너뛰고 원본을
 * 호출한다 — `function share() {}` 가 그대로 나와 **"없음"보다 나쁜 "가짜가 있음"**
 * 이 됐다. 게다가 native 함수엔 없는 own `toString` 프로퍼티가 남아 그 자체로
 * 표식이었다. 이제 `Function.prototype.toString`을 한 번만 프록시하고 우리가
 * 만든 함수(WeakSet 등록분)에 대해서만 `[native code]`를 돌려준다 — 나머지는
 * 원본에 그대로 위임하므로 함수 소스를 읽는 라이브러리는 영향을 받지 않는다.
 *
 * 함께: `window.outerWidth/Height`가 0일 때만 inner 값으로 교정(헤드리스 판별의
 * 고전 지표), 그리고 `Notification`을 채우면서 생긴 모순 —
 * `navigator.permissions.query({name:'notifications'})`가 거부되던 것 — 을
 * 'prompt'로 맞춰 준다.
 *
 * **v1.3.73 — 실기기 오페라 대조로 찾은 실제 버그.** 캡차 루프의 원인을 추측이
 * 아니라 직접 비교로 찾기 위해 오페라와 이 앱 양쪽에서 같은 진단 페이지를 열어
 * `navigator.permissions.query({name:'notifications'})` 결과를 대조했다. 오페라(정상
 * 통과)는 `prompt`, 이 앱은 `denied`. v1.3.63의 가정("WebView는 이 질의를
 * 거부(reject)한다")이 이 WebView 버전(150)에서는 틀렸다 — 거부되지 않고 정상
 * resolve되며 state가 바로 `denied`로 나온다. `catch`만 잡던 기존 패치가 이
 * 케이스를 아예 못 보고 있었으므로 resolve된 값도 함께 확인해 `denied`면
 * `prompt`로 맞춘다. (`window.outerWidth/Height`도 오페라 대비 눈에 띄게
 * 달랐지만 — 앱 내장 WebView는 구조적으로 "브라우저 UI"가 없어 outer≈inner —
 * 이건 숫자를 억지로 지어내는 것이라 손대지 않는다.)
 */
object BrowserEnvPatch {

    private const val TAG = "UserAgent"

    fun install(view: WebView) {
        // v1.3.67 — 껐다 켜며 비교할 수 있게 한다. 지우는 방향으로만 계속 더했는데
        // 진단이 `없음=[-]`를 찍고도 챌린지 루프가 그대로라면, 더한 것 자체가
        // 신호일 가능성을 한 번은 확인해야 한다.
        if (!EnvSpoofSwitch.enabled) {
            DebugLog.w(TAG, "JS 환경 위장 꺼짐(설정) — document-start 주입 생략")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            DebugLog.w(TAG, "document-start 주입 미지원 기기 — JS 환경 정규화 생략")
            return
        }
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf("*"))
            DebugLog.d(
                TAG,
                "JS 환경 정규화 주입: window.chrome + Notification + PaymentRequest / " +
                    "PublicKeyCredential / mediaSession / share / bluetooth / usb / " +
                    "presentation / getInstalledRelatedApps / speechSynthesis " +
                    "+ toString 마스킹 / outerWidth / permissions 일관성(resolve+reject)"
            )
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
                val spoof = if (EnvSpoofSwitch.enabled) "위장 켬" else "위장 끔"
                if (out.isNotBlank()) DebugLog.w(TAG, "JS 환경 진단($spoof) — $out")
                else DebugLog.w(TAG, "JS 환경 진단: 결과 없음(스크립트 실패?)")
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
    has('navigator.canShare', function () { return navigator.canShare; });
    has('speechSynthesis', function () { return window.speechSynthesis; });
    has('SpeechSynthesisUtterance', function () { return window.SpeechSynthesisUtterance; });
    has('BeforeInstallPromptEvent', function () { return window.BeforeInstallPromptEvent; });
    has('BatteryManager', function () { return window.BatteryManager; });
    has('navigator.getBattery', function () { return navigator.getBattery; });
    has('trustedTypes', function () { return window.trustedTypes; });
    has('navigator.userAgentData', function () { return navigator.userAgentData; });

    var extra = [];
    var val = function (label, get) {
      try {
        var v = get();
        extra.push(label + '=' + (v === undefined ? 'undef' : v));
      } catch (e) { extra.push(label + '=ERR'); }
    };
    // v1.3.63 — 존재 여부만이 아니라 **값**을 찍는다. 셰이프/일관성이 어긋난
    // 항목은 부재보다 강한 신호라, 무엇이 어긋나 있는지 로그가 직접 말하게 한다.
    val('outer', function () { return window.outerWidth + 'x' + window.outerHeight; });
    val('inner', function () { return window.innerWidth + 'x' + window.innerHeight; });
    val('screen', function () { return screen.width + 'x' + screen.height; });
    val('dpr', function () { return window.devicePixelRatio; });
    val('touch', function () { return navigator.maxTouchPoints; });
    val('cores', function () { return navigator.hardwareConcurrency; });
    val('mem', function () { return navigator.deviceMemory; });
    val('lang', function () { return navigator.languages.join('/'); });
    val('tz', function () { return Intl.DateTimeFormat().resolvedOptions().timeZone; });
    val('pdf', function () { return navigator.pdfViewerEnabled; });
    val('dispMode', function () {
      var modes = ['browser', 'standalone', 'minimal-ui', 'fullscreen'];
      for (var i = 0; i < modes.length; i++) {
        if (window.matchMedia('(display-mode: ' + modes[i] + ')').matches) { return modes[i]; }
      }
      return 'none';
    });
    val('notifPerm', function () { return window.Notification && Notification.permission; });
    val('chromeKeys', function () { return Object.keys(window.chrome).join('+') || '(empty)'; });
    val('uaPlatform', function () {
      return navigator.userAgentData.platform + '/mobile=' + navigator.userAgentData.mobile;
    });
    // 마스킹이 실제로 먹히는지 — 안티봇이 쓰는 그 형태 그대로 확인.
    val('maskShare', function () {
      return Function.prototype.toString.call(navigator.share).indexOf('[native code]') >= 0;
    });
    val('maskToString', function () {
      return Function.prototype.toString.call(Function.prototype.toString)
        .indexOf('[native code]') >= 0;
    });
    val('webgl', function () {
      var gl = document.createElement('canvas').getContext('webgl');
      var dbg = gl.getExtension('WEBGL_debug_renderer_info');
      return gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL);
    });
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
    return '없음=[' + (miss.join(', ') || '-') + ']  ' + extra.join(' ');
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
  // 우리가 만든 함수 목록. v1.3.63부터 `Function.prototype.toString`을 통째로
  // 프록시해 이 목록의 함수만 `[native code]`로 답한다 — 자체 `toString`을 심는
  // 예전 방식은 `Function.prototype.toString.call(fn)`(안티봇이 실제로 쓰는 형태)에
  // 전혀 안 먹히고, 오히려 native엔 없는 own `toString` 프로퍼티가 남아
  // "가짜가 있음"이라는 더 강한 신호가 됐다.
  var faked = (typeof WeakSet === 'function') ? new WeakSet() : null;
  var nativeLike = function (fn, name) {
    try { Object.defineProperty(fn, 'name', { value: name, configurable: true }); } catch (e) {}
    try { if (faked) { faked.add(fn); } } catch (e) {}
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

  // ---- v1.3.62: 진단이 이름을 직접 찍어준 나머지 9개 --------------------------
  // 전부 Chrome for Android엔 있고 WebView엔 없다. 하나만 걸려도 WebView로
  // 특정되므로 부분만 채우는 건 의미가 없어 한 번에 채운다.
  //
  // 원칙: **존재는 하되 "쓸 수 없다"고 정직하게 답한다.** 기능 감지(typeof/in)는
  // 통과하지만 capability 질의(canShare / canMakePayment / getAvailability /
  // isUserVerifyingPlatformAuthenticatorAvailable)는 false를 돌려주므로, 사이트가
  // 눌러도 아무 일 없는 버튼을 새로 그리지 않는다.
  var navProto = (window.Navigator && window.Navigator.prototype) || navigator;

  var defGet = function (obj, name, getter) {
    try {
      if (name in obj) { return; }
      Object.defineProperty(obj, name, {
        get: nativeLike(getter, 'get ' + name), configurable: true, enumerable: true
      });
    } catch (e) {}
  };
  var defVal = function (obj, name, value, enumerable) {
    try {
      if (name in obj) { return; }
      Object.defineProperty(obj, name, {
        value: value, configurable: true, writable: true, enumerable: !!enumerable
      });
    } catch (e) {}
  };
  var fail = function (name, message) {
    try { return Promise.reject(new DOMException(message, name)); }
    catch (e) { return Promise.reject(new Error(message)); }
  };
  var noop = function (label) { return nativeLike(function () {}, label); };

  try {
    defVal(navProto, 'share', nativeLike(function (data) {
      return fail('AbortError', 'Share canceled');
    }, 'share'), true);
    defVal(navProto, 'canShare', nativeLike(function (data) { return false; }, 'canShare'), true);
  } catch (e) {}

  try {
    defVal(navProto, 'getInstalledRelatedApps', nativeLike(function () {
      return Promise.resolve([]);
    }, 'getInstalledRelatedApps'), true);
  } catch (e) {}

  try {
    var mediaSession = {
      metadata: null,
      playbackState: 'none',
      setActionHandler: noop('setActionHandler'),
      setPositionState: noop('setPositionState'),
      setMicrophoneActive: noop('setMicrophoneActive'),
      setCameraActive: noop('setCameraActive')
    };
    defGet(navProto, 'mediaSession', function () { return mediaSession; });
  } catch (e) {}

  try {
    var bluetooth = {
      getAvailability: nativeLike(function () { return Promise.resolve(false); }, 'getAvailability'),
      requestDevice: nativeLike(function () {
        return fail('NotFoundError', 'User cancelled the requestDevice() chooser.');
      }, 'requestDevice'),
      getDevices: nativeLike(function () { return Promise.resolve([]); }, 'getDevices'),
      addEventListener: noop('addEventListener'),
      removeEventListener: noop('removeEventListener'),
      dispatchEvent: nativeLike(function () { return false; }, 'dispatchEvent')
    };
    defGet(navProto, 'bluetooth', function () { return bluetooth; });
  } catch (e) {}

  try {
    var usb = {
      getDevices: nativeLike(function () { return Promise.resolve([]); }, 'getDevices'),
      requestDevice: nativeLike(function () {
        return fail('NotFoundError', 'No device selected.');
      }, 'requestDevice'),
      addEventListener: noop('addEventListener'),
      removeEventListener: noop('removeEventListener'),
      dispatchEvent: nativeLike(function () { return false; }, 'dispatchEvent')
    };
    defGet(navProto, 'usb', function () { return usb; });
  } catch (e) {}

  try {
    var presentation = { defaultRequest: null, receiver: null };
    defGet(navProto, 'presentation', function () { return presentation; });
  } catch (e) {}

  try {
    if (typeof window.PaymentRequest === 'undefined') {
      var PR = function PaymentRequest(methodData, details, options) {
        if (!(this instanceof PR)) {
          throw new TypeError(
            "Failed to construct 'PaymentRequest': Please use the 'new' operator."
          );
        }
        if (arguments.length < 2) {
          throw new TypeError(
            "Failed to construct 'PaymentRequest': 2 arguments required."
          );
        }
        this.id = String(Math.random()).slice(2);
        this.shippingAddress = null;
        this.shippingOption = null;
        this.shippingType = null;
        this.onshippingaddresschange = null;
        this.onshippingoptionchange = null;
        this.onpaymentmethodchange = null;
      };
      nativeLike(PR, 'PaymentRequest');
      PR.prototype.show = nativeLike(function () {
        return fail('NotSupportedError', 'The payment method is not supported.');
      }, 'show');
      PR.prototype.abort = nativeLike(function () { return Promise.resolve(); }, 'abort');
      PR.prototype.canMakePayment = nativeLike(function () {
        return Promise.resolve(false);
      }, 'canMakePayment');
      PR.prototype.addEventListener = noop('addEventListener');
      PR.prototype.removeEventListener = noop('removeEventListener');
      defVal(window, 'PaymentRequest', PR, false);
    }
  } catch (e) {}

  try {
    if (typeof window.PublicKeyCredential === 'undefined') {
      var PKC = function PublicKeyCredential() {
        throw new TypeError('Illegal constructor');
      };
      nativeLike(PKC, 'PublicKeyCredential');
      PKC.isUserVerifyingPlatformAuthenticatorAvailable = nativeLike(function () {
        return Promise.resolve(false);
      }, 'isUserVerifyingPlatformAuthenticatorAvailable');
      PKC.isConditionalMediationAvailable = nativeLike(function () {
        return Promise.resolve(false);
      }, 'isConditionalMediationAvailable');
      defVal(window, 'PublicKeyCredential', PKC, false);
    }
  } catch (e) {}

  try {
    if (typeof window.speechSynthesis === 'undefined') {
      var SSU = function SpeechSynthesisUtterance(text) {
        this.text = text === undefined ? '' : String(text);
        this.lang = ''; this.voice = null;
        this.volume = 1; this.rate = 1; this.pitch = 1;
        this.onstart = null; this.onend = null; this.onerror = null;
        this.onpause = null; this.onresume = null;
        this.onmark = null; this.onboundary = null;
      };
      nativeLike(SSU, 'SpeechSynthesisUtterance');
      SSU.prototype.addEventListener = noop('addEventListener');
      SSU.prototype.removeEventListener = noop('removeEventListener');
      var synth = {
        pending: false, speaking: false, paused: false, onvoiceschanged: null,
        getVoices: nativeLike(function () { return []; }, 'getVoices'),
        speak: noop('speak'), cancel: noop('cancel'),
        pause: noop('pause'), resume: noop('resume'),
        addEventListener: noop('addEventListener'),
        removeEventListener: noop('removeEventListener'),
        dispatchEvent: nativeLike(function () { return false; }, 'dispatchEvent')
      };
      defVal(window, 'SpeechSynthesisUtterance', SSU, false);
      defGet(window, 'speechSynthesis', function () { return synth; });
    }
  } catch (e) {}

  // ---- v1.3.63 ---------------------------------------------------------------

  // WebView는 `window.outerWidth/outerHeight`가 0으로 나오는 경우가 있다.
  // 헤드리스 판별의 고전적 지표라 0일 때만(= 명백히 틀렸을 때만) inner 값으로 맞춘다.
  try {
    if (window.outerWidth === 0 || window.outerHeight === 0) {
      Object.defineProperty(window, 'outerWidth', {
        get: nativeLike(function () { return window.innerWidth; }, 'get outerWidth'),
        configurable: true, enumerable: true
      });
      Object.defineProperty(window, 'outerHeight', {
        get: nativeLike(function () { return window.innerHeight; }, 'get outerHeight'),
        configurable: true, enumerable: true
      });
    }
  } catch (e) {}

  // v1.3.60이 `Notification`을 채우면서 생긴 **모순**을 없앤다.
  // 진짜 Chrome은 `Notification.permission === 'default'` 면
  // `navigator.permissions.query({name:'notifications'})` 가 'prompt'를 준다.
  // WebView는 알림 자체를 모르니 이 질의가 거부(reject)될 거라 예상했는데,
  // 실기기 진단(fingerprint-scan 비교, v1.3.73)에서 이 WebView 버전(150)은
  // **거부하지 않고 정상 resolve하면서 state:'denied'를 준다** — 그래서
  // catch만 잡던 기존 패치가 한 번도 발동하지 않았다. resolve된 값도 함께 본다.
  try {
    var perms = navigator.permissions;
    if (perms && typeof perms.query === 'function') {
      var origQuery = perms.query.bind(perms);
      var synthetic = function (name, state) {
        return {
          name: name, state: state, status: state,
          onchange: null,
          addEventListener: noop('addEventListener'),
          removeEventListener: noop('removeEventListener'),
          dispatchEvent: nativeLike(function () { return false; }, 'dispatchEvent')
        };
      };
      var patched = nativeLike(function query(desc) {
        var name = desc && desc.name;
        return origQuery(desc).then(function (status) {
          if (name === 'notifications' && status && status.state === 'denied') {
            return synthetic(name, 'prompt');
          }
          return status;
        })['catch'](function (err) {
          if (name === 'notifications') { return synthetic(name, 'prompt'); }
          if (name === 'payment-handler') { return synthetic(name, 'denied'); }
          throw err;
        });
      }, 'query');
      Object.defineProperty(perms, 'query', {
        value: patched, configurable: true, writable: true, enumerable: false
      });
    }
  } catch (e) {}

  // **마지막에 설치한다** — 위에서 만든 shim이 전부 `faked`에 등록된 뒤라야 한다.
  // 우리 함수만 `[native code]`로 답하고 나머지는 원본에 그대로 위임하므로,
  // 함수 소스를 들여다보는 라이브러리(DI 컨테이너 등)는 영향을 받지 않는다.
  try {
    if (faked) {
      var origToString = Function.prototype.toString;
      var masked = function toString() {
        try {
          if (faked.has(this)) {
            return 'function ' + (this.name || '') + '() { [native code] }';
          }
        } catch (e) {}
        return origToString.call(this);
      };
      nativeLike(masked, 'toString');
      faked.add(origToString);
      Object.defineProperty(Function.prototype, 'toString', {
        value: masked, configurable: true, writable: true, enumerable: false
      });
    }
  } catch (e) {}
})();
""".trimIndent()
}
