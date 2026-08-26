package com.playerbrowser.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Message
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import com.playerbrowser.app.cast.VideoStreamSniffer
import com.playerbrowser.app.network.AdBlockSwitch
import com.playerbrowser.app.network.AdBlocker
import com.playerbrowser.app.network.ChallengeDetector
import com.playerbrowser.app.network.CookieBannerKiller
import com.playerbrowser.app.network.CookieBannerSwitch
import com.playerbrowser.app.network.CrashRecorder
import com.playerbrowser.app.network.DebugLog
import com.playerbrowser.app.network.EnvSpoofSwitch
import com.playerbrowser.app.network.LinkNewTabSwitch
import com.playerbrowser.app.network.SniBypassClient
import com.playerbrowser.app.network.UrlRecovery
import com.playerbrowser.app.network.UserAgentSpoof
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.playerbrowser.app.web.BrowserEnvPatch
import com.playerbrowser.app.web.IframeScriptInjector
import com.playerbrowser.app.web.PlayerBridge
import com.playerbrowser.app.web.ResumeBridge
import com.playerbrowser.app.web.WebAssetLoader

class BrowserWebViewState(
    val webView: WebView,
    private val callbacks: WebViewCallbacks
) {
    fun load(url: String) { webView.loadUrl(url) }
    fun goBack(): Boolean { if (webView.canGoBack()) { webView.goBack(); return true }; return false }
    fun goForward() { if (webView.canGoForward()) webView.goForward() }
    fun reload() { webView.reload() }
    fun canGoBack(): Boolean = webView.canGoBack()
    fun canGoForward(): Boolean = webView.canGoForward()
}

interface WebViewCallbacks {
    fun onStarted(url: String)
    fun onFinished(url: String, title: String, canGoBack: Boolean, canGoForward: Boolean)
    fun onOpenAppSettings()
    fun onOpenInNewTab(url: String)
    // Open a link in a new tab without switching to it (link long-press
    // "백그라운드 탭으로 열기"). Default routes to foreground so implementers that
    // don't care about backgrounding still work.
    fun onOpenInBackgroundTab(url: String) = onOpenInNewTab(url)
    // A <video> was long-pressed; open it in the external player. `domSrc` is the
    // element's source URL ("" when unresolvable — caller falls back to sniffer).
    fun onPlayVideoExternally(domSrc: String) {}
}

@SuppressLint("SetJavaScriptEnabled")
fun buildBrowserWebView(context: Context, callbacks: WebViewCallbacks): BrowserWebViewState {
    // "이어보기" bridge — keyed by the page URL kept in sync via the WebViewClient.
    val resumeBridge = ResumeBridge(context.applicationContext)
    val playerBridge = PlayerBridge { src -> callbacks.onPlayVideoExternally(src) }
    val webView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // 브라우저 위장 — 토글 하나가 **전부** 를 끈다 (v1.3.71).
            // 예전엔 이 토글이 JS 환경 주입만 껐고 UA/Sec-CH-UA 는 그대로
            // 적용돼서, "끄고 테스트" 가 순정 WebView 테스트가 아니었다.
            // 이제 끄면 진짜 아무것도 안 한 기본 WebView 로 나간다.
            if (EnvSpoofSwitch.enabled) {
                // `; wv` / `Version/4.0` 표식 제거 (v1.3.58).
                userAgentString = UserAgentSpoof.chromeLike(userAgentString)
                // UA 문자열과 `Sec-CH-UA` 브랜드를 짝 맞춤 (v1.3.59).
                UserAgentSpoof.applyClientHints(this, userAgentString)
                // WebView가 모든 요청에 붙이는 `X-Requested-With: <패키지명>` 제거 —
                // 진짜 Chrome 은 이 헤더를 안 보낸다 (v1.3.71).
                UserAgentSpoof.stripRequestedWithHeader(this)
            } else {
                DebugLog.d("UserAgent", "브라우저 위장 꺼짐 — 순정 WebView UA/헤더/JS 그대로")
            }
        }
        CookieManager.getInstance().also { cm ->
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
        }
        // 페이지 스크립트보다 먼저 실행돼 WebView 특유의 JS 환경 표식을 지운다
        // (window.chrome / Notification 부재). 네트워크는 건드리지 않는다 (v1.3.60).
        BrowserEnvPatch.install(this)
        addJavascriptInterface(resumeBridge, "PBResume")
        // `window.PBPlayer` — video long-press → external player (see PlayerBridge).
        addJavascriptInterface(playerBridge, "PBPlayer")
        val gestureScript = WebAssetLoader.gestureScript(context)
        IframeScriptInjector.setScript(gestureScript)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (request == null) return false
                val uri = request.url ?: return false
                // Internal schemes / external apps are routed first; route()
                // returns false for plain http/https so we can decide below.
                if (UrlIntentRouter.route(view?.context ?: context, uri, callbacks)) return true

                // "링크를 항상 새 탭에서 열기": spin a user-clicked main-frame link
                // off into a child tab instead of replacing the current page.
                // Gated to genuine link taps — typed URLs (loadUrl never hits this
                // callback), server redirects, and JS navigations without a user
                // gesture are left to load in place.
                // GET만 대상 — 폼 전송(POST)을 새 탭으로 돌리면 본문이 사라진
                // 맨 GET으로 다시 열려 흐름이 깨진다(캡차 통과 직후의 챌린지
                // 폼이 대표적: 새 탭에서 처음부터 다시 "사람인지 확인"이 뜸).
                if (LinkNewTabSwitch.enabled &&
                    request.isForMainFrame &&
                    request.hasGesture() &&
                    !request.isRedirect &&
                    request.method?.equals("GET", ignoreCase = true) != false
                ) {
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") {
                        callbacks.onOpenInNewTab(uri.toString())
                        return true
                    }
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                // 챌린지 호스트는 응답을 만들어내는 단계에서 전부 손을 뗀다 —
                // 광고차단이 돌려주는 빈 204도 챌린지 스크립트에겐 로드 실패다.
                // (스니퍼는 요청을 관찰만 하므로 그대로 둔다 — 격리됐다고 그
                //  사이트의 동영상 감지까지 죽으면 정작 쓸 수가 없다.)
                if (ChallengeDetector.isQuarantinedHost(request.url?.host)) {
                    VideoStreamSniffer.observe(request, view?.tag as? String)
                    return null
                }
                // The sniffer runs before the ad blocker even though it is the
                // more expensive of the two: it only *observes*, and a request
                // the blocker swallows is a request it would never see. That
                // blind spot is invisible until a page's stream happens to sit
                // behind a blocked pattern and "no stream" is all the user gets.
                VideoStreamSniffer.observe(request, view?.tag as? String)
                // Ad blocker next — cheapest check, returns an empty 204
                // before we waste cycles on SNI / iframe injection.
                AdBlocker.intercept(request)?.let { return it }
                val bypassed = SniBypassClient.intercept(request)
                return IframeScriptInjector.process(request, bypassed)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Track host so the sniffer attributes captured stream URLs to
                // the right page even when subresources come from CDNs.
                val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                view?.tag = host
                // `window.PBResume` / `window.PBPlayer` 같은 네이티브 브리지는
                // 페이지가 window를 훑으면 그대로 보인다 — 안티봇이 자동화
                // 브라우저로 판정하는 대표적 신호다. 챌린지 호스트에선 떼어낸다
                // (챌린지 페이지엔 비디오도 이어보기도 없다).
                if (view != null) {
                    runCatching {
                        if (ChallengeDetector.isChallengeActive(host)) {
                            view.removeJavascriptInterface("PBResume")
                            view.removeJavascriptInterface("PBPlayer")
                        } else {
                            view.addJavascriptInterface(resumeBridge, "PBResume")
                            view.addJavascriptInterface(playerBridge, "PBPlayer")
                        }
                    }
                }
                resumeBridge.currentUrl = url
                url?.let { callbacks.onStarted(it) }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) resumeBridge.currentUrl = url
                // 챌린지 페이지엔 아무것도 주입하지 않는다. 제스처 스크립트는
                // document에 캡처 리스너를 걸고, 광고차단 CSS는 위젯을 가릴 수
                // 있고, 쿠키배너 킬러는 버튼을 눌러댄다 — 안티봇 입장에선 전부
                // 자동화 신호이고 챌린지엔 어차피 쓸모가 없다.
                val onChallenge = ChallengeDetector.isChallengeActive(
                    url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                )
                if (!onChallenge) {
                    // 이 기기의 WebView에 실제로 뭐가 빠져 있는지 1회 진단 (v1.3.61).
                    view?.let { BrowserEnvPatch.probeEnvironment(it) }
                    view?.evaluateJavascript(gestureScript, null)
                    if (AdBlockSwitch.enabled) {
                        view?.evaluateJavascript(AdBlocker.HIDE_CSS_JS, null)
                    }
                    if (CookieBannerSwitch.enabled) {
                        view?.evaluateJavascript(CookieBannerKiller.SCRIPT, null)
                    }
                }
                // "사람인지 확인" 위젯이 떠 있는지 살펴 디버그 로그에 기록
                // (어떤 사이트가 어떤 캡차를 쓰는지 / 루프에 빠졌는지 추적용).
                ChallengeDetector.probe(view, url)
                if (view != null && url != null) {
                    callbacks.onFinished(
                        url = url,
                        title = view.title.orEmpty(),
                        canGoBack = view.canGoBack(),
                        canGoForward = view.canGoForward()
                    )
                }
            }
            // 진단 전용 — 동작은 바꾸지 않는다 (v1.3.64).
            // 격리 호스트의 메인 프레임은 우리 OkHttp를 안 거치므로 응답 코드를
            // 볼 방법이 없었다. 챌린지 루프가 도는 동안 매 라운드의 상태 코드와
            // Cloudflare 헤더(`cf-mitigated`/`cf-ray`)를 남겨, 새로 발급된
            // `cf_clearance`가 실제로 받아들여지는지(200) 여전히 거부되는지(403)를
            // 한 줄로 확인할 수 있게 한다.
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && errorResponse != null) {
                    ChallengeDetector.noteHttpError(
                        request.url,
                        errorResponse.statusCode,
                        errorResponse.responseHeaders
                    )
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (view == null || request == null || error == null) return
                if (!request.isForMainFrame) return
                val failingUrl = request.url?.toString().orEmpty()
                val code = error.errorCode
                val desc = error.description?.toString().orEmpty()

                // 캡차 때문에 네이티브 전담(격리)으로 돌린 호스트가 접속 자체에
                // 실패했다면 DPI에 막힌 것이다 — 네이티브 경로엔 ClientHello
                // 단편화가 없다. 격리를 풀어 다음 시도는 다시 SNI 우회 경로로
                // 나가게 한다(캡차 루프보다 접속 불가가 더 나쁘다).
                if (UrlRecovery.shouldProbe(code)) {
                    ChallengeDetector.clearQuarantine(
                        runCatching { Uri.parse(failingUrl).host }.getOrNull()
                    )
                }

                // URL의 숫자가 바뀐 사이트(예: newtoki123 → newtoki124)일 수 있으니,
                // 접속 실패성 에러면 숫자 증감 후보를 백그라운드로 확인해 살아있는
                // 주소로 자동 이동한다. 후보가 없거나 못 찾으면 평소 에러 페이지.
                val recoveryCandidates =
                    if (UrlRecovery.shouldProbe(code)) UrlRecovery.candidates(failingUrl)
                    else emptyList()
                if (recoveryCandidates.isNotEmpty()) {
                    view.loadDataWithBaseURL(
                        failingUrl, ErrorPage.probing(failingUrl), "text/html", "UTF-8", failingUrl
                    )
                    UrlRecovery.probe(failingUrl, recoveryCandidates) { found ->
                        // probe 콜백은 백그라운드 스레드 + 최대 8초 뒤 → 그 사이 탭이
                        // 닫혀 WebView가 destroy 됐을 수 있다. post / load 모두 보호.
                        runCatching {
                            view.post {
                                runCatching {
                                    if (found != null) {
                                        Toast.makeText(
                                            view.context,
                                            "주소가 변경된 것 같아 이동합니다",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        view.loadUrl(found)
                                    } else {
                                        view.loadDataWithBaseURL(
                                            failingUrl,
                                            ErrorPage.build(failingUrl, code, desc),
                                            "text/html", "UTF-8", failingUrl
                                        )
                                    }
                                }
                            }
                        }
                    }
                    return
                }

                val html = ErrorPage.build(failingUrl, code, desc)
                view.loadDataWithBaseURL(failingUrl, html, "text/html", "UTF-8", failingUrl)
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // The WebView renderer crashed (OOM, video-decoder fault, etc).
                // Returning true keeps the host app alive; we surface an error
                // page and record the event so the user can see what happened.
                val didCrash = detail?.didCrash() == true
                val priority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    detail?.rendererPriorityAtExit() else null
                val msg = "WebView renderer gone didCrash=$didCrash priority=$priority"
                DebugLog.e("WebView", msg)
                val ctx = view?.context
                if (ctx != null) {
                    CrashRecorder.record(ctx, "WebViewRenderer", msg)
                    runCatching {
                        Toast.makeText(
                            ctx,
                            "웹페이지가 종료되었습니다. 새로고침해 주세요.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                if (view != null) {
                    runCatching {
                        val html = ErrorPage.build("about:blank", -1, "renderer crashed")
                        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    }
                }
                return true
            }
        }
        webChromeClient = FullscreenAwareChromeClient(this, callbacks)
        // Long-press a link → context menu ("새 탭에서 열기" / "백그라운드 탭으로
        // 열기" / "링크 주소 복사"). Standard browser affordance so the user no
        // longer has to flip the global "always open in new tab" setting.
        setOnLongClickListener { v ->
            val wv = v as? WebView ?: return@setOnLongClickListener false
            val result = wv.hitTestResult
            if (result.type != WebView.HitTestResult.SRC_ANCHOR_TYPE &&
                result.type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                return@setOnLongClickListener false
            }
            // For SRC_ANCHOR_TYPE `extra` is already the href; for an image inside
            // an anchor it's the image src, so ask the WebView for the node's href
            // and fall back to `extra` when it doesn't answer.
            val handler = android.os.Handler(android.os.Looper.getMainLooper()) { msg ->
                val href = msg.data?.getString("url")?.takeIf { it.isNotBlank() }
                    ?: result.extra
                showLinkContextMenu(wv.context, href, callbacks)
                true
            }
            wv.requestFocusNodeHref(handler.obtainMessage())
            true
        }
    }
    return BrowserWebViewState(webView, callbacks)
}

/**
 * Shows the link long-press context menu. Only surfaces for http(s) links —
 * anything else (javascript:, mailto:, relative fragments) is ignored.
 */
private fun showLinkContextMenu(context: Context, url: String?, callbacks: WebViewCallbacks) {
    val href = url?.trim().orEmpty()
    val scheme = runCatching { Uri.parse(href).scheme?.lowercase() }.getOrNull()
    if (scheme != "http" && scheme != "https") return

    val items = arrayOf("새 탭에서 열기", "백그라운드 탭으로 열기", "링크 주소 복사")
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle(href)
        .setItems(items) { _, which ->
            when (which) {
                0 -> callbacks.onOpenInNewTab(href)
                1 -> {
                    callbacks.onOpenInBackgroundTab(href)
                    Toast.makeText(context, "백그라운드 탭에서 열었어요", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("link", href))
                    Toast.makeText(context, "링크 주소를 복사했어요", Toast.LENGTH_SHORT).show()
                }
            }
        }
        .show()
}

private class FullscreenAwareChromeClient(
    private val webView: WebView,
    private val callbacks: WebViewCallbacks
) : WebChromeClient() {
    private var customView: View? = null
    private var customViewContainer: ViewGroup? = null
    private var customViewCallback: CustomViewCallback? = null
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        // target="_blank" / window.open(): spin up a throwaway WebView purely
        // to extract the resolved URL, then hand it to the host so it can spawn
        // a real new tab. The older "transport.webView = view" trick silently
        // dropped popups on some sites.
        if (view == null || resultMsg == null) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val popup = WebView(view.context).apply {
            settings.javaScriptEnabled = true
            var delivered = false
            fun deliver(url: String?, v: WebView?) {
                if (delivered) return
                if (url.isNullOrEmpty() || url == "about:blank") return
                delivered = true
                callbacks.onOpenInNewTab(url)
                v?.stopLoading()
                v?.destroy()
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    deliver(request?.url?.toString(), v)
                    return true
                }

                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    deliver(url, v)
                }
            }
        }
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        val activity = webView.context as? Activity ?: return
        customView = view
        customViewCallback = callback
        savedOrientation = activity.requestedOrientation

        val decor = activity.window.decorView as ViewGroup
        val container = GestureCapturingFrame(activity, webView)
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        decor.addView(
            container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        customViewContainer = container

        // Tell the in-document gesture script to stand down: this overlay is now
        // the single gesture source, otherwise a double-tap seeks twice.
        webView.evaluateJavascript("window.__pb && (window.__pb.fsActive = true);", null)

        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, decor)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Default to landscape immediately so most videos rotate without waiting on JS.
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        applyVideoOrientation(activity, 0)
    }

    /**
     * Match screen orientation to the video's aspect ratio (portrait video →
     * portrait screen, landscape → landscape). The video's intrinsic size may
     * not be known yet at the moment fullscreen opens, so when the probe can't
     * tell ("unknown") we retry a few times before giving up on the landscape
     * default — that's what makes a portrait video actually rotate to portrait
     * instead of being stuck landscape.
     */
    private fun applyVideoOrientation(activity: Activity, attempt: Int) {
        if (customView == null) return
        webView.evaluateJavascript(DETECT_VIDEO_ORIENTATION_JS) { result ->
            if (customView == null) return@evaluateJavascript
            when (result?.trim('"')) {
                "port" -> activity.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                "land" -> activity.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> if (attempt < 8) {
                    // Dimensions not ready — re-probe shortly (keeps landscape default meanwhile).
                    webView.postDelayed({ applyVideoOrientation(activity, attempt + 1) }, 200)
                }
            }
        }
    }

    override fun onHideCustomView() {
        customView ?: return
        val activity = webView.context as? Activity
        val container = customViewContainer
        customView = null
        customViewContainer = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null

        // Re-enable the in-document gesture handlers now that the overlay is gone.
        webView.evaluateJavascript("window.__pb && (window.__pb.fsActive = false);", null)

        if (activity != null) {
            val decor = activity.window.decorView as ViewGroup
            if (container != null) decor.removeView(container)
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
            WindowInsetsControllerCompat(activity.window, decor)
                .show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = savedOrientation
            // Restore system-default brightness in case the vertical-drag
            // gesture overrode it during fullscreen playback.
            val lp = activity.window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = lp
        }
        savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    companion object {
        private const val DETECT_VIDEO_ORIENTATION_JS = """
            (function () {
              var v = document.fullscreenElement || document.webkitFullscreenElement;
              if (!v || v.tagName !== 'VIDEO') {
                var vs = document.querySelectorAll('video');
                for (var i = 0; i < vs.length; i++) {
                  if (vs[i].videoWidth && vs[i].videoHeight) { v = vs[i]; break; }
                }
              }
              if (v && v.videoWidth && v.videoHeight) {
                return v.videoWidth >= v.videoHeight ? 'land' : 'port';
              }
              return 'unknown';
            })();
        """
    }
}

/**
 * Wraps the WebView's native-fullscreen CustomView so we can observe every
 * touch event regardless of which child view consumes it.
 *
 * Why: WebView hands the video surface to onShowCustomView as an arbitrary
 * ViewGroup. If we attach an OnTouchListener directly to that view, any
 * child that returns true from onTouchEvent (e.g. an HTML5 controls overlay)
 * stops the event from ever reaching our listener. By overriding
 * dispatchTouchEvent here we run our gesture detection in parallel with —
 * not after — the child view tree, while still allowing the player's own
 * touch handling to proceed.
 */
private class GestureCapturingFrame(
    context: Context,
    private val webView: WebView
) : FrameLayout(context) {

    private enum class VbMode { Volume, Brightness }

    private val swipeThresholdPx = 40f * resources.displayMetrics.density
    private val vbThresholdPx = 24f * resources.displayMetrics.density
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val maxSwipeMs = 800L

    private var startX = 0f
    private var startY = 0f
    private var startT = 0L
    private var moved = false
    private var maxPointers = 1
    private var siteCancelled = false

    private var vbAdjust: VbMode? = null
    private var vbStartValue: Float = 0f
    private var lastVbFireMs: Long = 0L

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val maxVolume: Int by lazy {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
    }

    // Stopgap (v1.3.42): forward raw touches to the WebView so the *site's own*
    // player controls work natively again — taps reveal its control layer, its
    // own double-tap-seek / scrubber respond, and a native <video controls> bar
    // shows on tap. The previous "fully consume, drive everything via
    // window.__pb.* hooks" approach went dead whenever the real <video> lived in
    // a cross-origin iframe the top-frame hooks couldn't reach (touch felt
    // unresponsive). Here we instead let the site own taps/double-taps/scrub and
    // layer ONLY the two gestures sites never provide — vertical
    // brightness/volume and a 2-finger video switch. When one of those engages
    // we send ACTION_CANCEL down so the site stops tracking the same drag. The
    // in-document gesture script stays out via fsActive=true. The full app
    // gesture set lives in the native Media3 player instead.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                startT = System.currentTimeMillis()
                moved = false
                maxPointers = 1
                siteCancelled = false
                vbAdjust = null
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount > maxPointers) maxPointers = ev.pointerCount
                if (maxPointers >= 2 && !siteCancelled) {
                    // Two fingers → reserve for a switchVideo swipe; stop the
                    // site tracking the first finger so it doesn't also act.
                    cancelChildren(ev)
                    siteCancelled = true
                }
                if (vbAdjust != null) {
                    vbAdjust = null
                    hideVbOverlay()
                }
                return if (siteCancelled) true else super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                if (!moved &&
                    (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)
                ) {
                    moved = true
                }
                if (maxPointers >= 2) {
                    // 2-finger swipe → switchVideo on UP; consume (site cancelled).
                    return true
                }
                // Engage vertical brightness/volume once a clear vertical drag is
                // seen, then cancel the site's tracking of this same drag.
                if (vbAdjust == null && abs(dy) > vbThresholdPx && abs(dy) > abs(dx)) {
                    val w = width.coerceAtLeast(1)
                    val mode = if (startX / w < 0.5f) VbMode.Brightness else VbMode.Volume
                    vbAdjust = mode
                    vbStartValue = when (mode) {
                        VbMode.Volume -> currentVolumeIndex().toFloat()
                        VbMode.Brightness -> currentBrightnessRatio()
                    }
                    if (!siteCancelled) {
                        cancelChildren(ev)
                        siteCancelled = true
                    }
                }
                val mode = vbAdjust
                if (mode != null) {
                    val h = height.coerceAtLeast(1)
                    // Y grows downward — invert so dragging up increases.
                    val deltaRatio = -dy / h
                    when (mode) {
                        VbMode.Volume -> {
                            val maxVol = maxVolume
                            if (maxVol > 0) {
                                val newIdx = (vbStartValue + deltaRatio * maxVol)
                                    .coerceIn(0f, maxVol.toFloat())
                                audioManager?.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    newIdx.toInt(),
                                    0
                                )
                                fireVbOverlay("volume", newIdx / maxVol)
                            }
                        }
                        VbMode.Brightness -> {
                            val newRatio = (vbStartValue + deltaRatio).coerceIn(0f, 1f)
                            applyBrightness(newRatio)
                            fireVbOverlay("brightness", newRatio)
                        }
                    }
                    return true
                }
                // Otherwise forward to the site so its own scrubber/controls work.
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                val dt = System.currentTimeMillis() - startT
                if (vbAdjust != null) {
                    vbAdjust = null
                    hideVbOverlay()
                    return true
                }
                if (maxPointers >= 2) {
                    if (moved && dt <= maxSwipeMs &&
                        abs(dx) >= swipeThresholdPx && abs(dx) > abs(dy)
                    ) {
                        val dir = if (dx > 0) -1 else 1
                        webView.evaluateJavascript(
                            "window.__pb && window.__pb.switchVideo && window.__pb.switchVideo($dir);",
                            null
                        )
                    }
                    return true
                }
                // Plain tap / forwarded drag → let the site complete it (reveals
                // controls, toggles its own play/pause, etc).
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (vbAdjust != null) {
                    vbAdjust = null
                    hideVbOverlay()
                }
                return super.dispatchTouchEvent(ev)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun cancelChildren(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(
            ev.downTime,
            ev.eventTime,
            MotionEvent.ACTION_CANCEL,
            ev.x,
            ev.y,
            ev.metaState
        )
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun hideVbOverlay() {
        webView.evaluateJavascript(
            "window.__pb && window.__pb.hideVbOverlay && window.__pb.hideVbOverlay();",
            null
        )
    }

    private fun currentVolumeIndex(): Int =
        audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

    private fun currentBrightnessRatio(): Float {
        val window = (webView.context as? Activity)?.window ?: return 0.5f
        val current = window.attributes.screenBrightness
        if (current in 0f..1f) return current
        return runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            ) / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
    }

    private fun applyBrightness(ratio: Float) {
        val window = (webView.context as? Activity)?.window ?: return
        val lp = window.attributes
        lp.screenBrightness = ratio
        window.attributes = lp
    }

    private fun fireVbOverlay(kind: String, ratio: Float) {
        val now = System.currentTimeMillis()
        if (now - lastVbFireMs < 33) return
        lastVbFireMs = now
        val clamped = ratio.coerceIn(0f, 1f)
        webView.evaluateJavascript(
            "window.__pb && window.__pb.showVbOverlay && " +
                "window.__pb.showVbOverlay('$kind', $clamped);",
            null
        )
    }
}

@Composable
fun BrowserWebViewHost(
    state: BrowserWebViewState,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { container ->
            val wv = state.webView
            if (wv.parent !== container) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                container.removeAllViews()
                container.addView(wv)
            }
        }
    )
}

private object UrlIntentRouter {
    private const val INTERNAL_SCHEME = "playerbrowser"

    fun route(context: Context, uri: Uri, callbacks: WebViewCallbacks): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return when {
            scheme == INTERNAL_SCHEME -> handleInternal(context, uri, callbacks)
            scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" ||
                scheme == "javascript" || scheme == "file" -> false
            else -> handleExternal(context, uri)
        }
    }

    private fun handleInternal(context: Context, uri: Uri, callbacks: WebViewCallbacks): Boolean {
        when (uri.host?.lowercase()) {
            "settings" -> callbacks.onOpenAppSettings()
            "private-dns" -> openPrivateDnsSettings(context)
            "install-warp" -> openPlayStore(context, "com.cloudflare.onedotonedotonedotone")
            "update-webview" -> openPlayStore(context, "com.google.android.webview")
            else -> {
                Toast.makeText(context, "지원하지 않는 동작: $uri", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun openPlayStore(context: Context, packageName: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(market) }.isSuccess) return
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(web) }
            .onFailure {
                Toast.makeText(context, "Play 스토어를 열 수 없습니다", Toast.LENGTH_SHORT).show()
            }
    }

    private fun openPrivateDnsSettings(context: Context) {
        val attempts = listOf(
            Intent("android.settings.WIRELESS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { context.startActivity(intent) }.isSuccess
            if (ok) {
                Toast.makeText(
                    context,
                    "설정 → 연결/네트워크 → 비공개 DNS(Private DNS) → " +
                        "'1dot1dot1dot1.cloudflare-dns.com' 입력",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
        Toast.makeText(context, "시스템 설정 화면을 열 수 없습니다", Toast.LENGTH_SHORT).show()
    }

    private fun handleExternal(context: Context, uri: Uri): Boolean {
        val intent = parseIntent(uri) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (!fallback.isNullOrBlank()) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(fallback))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                true
            } else {
                Toast.makeText(context, "이 링크를 열 수 있는 앱이 없습니다", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun parseIntent(uri: Uri): Intent? = runCatching {
        if (uri.scheme.equals("intent", ignoreCase = true)) {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
    }.getOrNull()
}
