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
import android.view.GestureDetector
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
import com.playerbrowser.app.network.CookieBannerKiller
import com.playerbrowser.app.network.CookieBannerSwitch
import com.playerbrowser.app.network.CrashRecorder
import com.playerbrowser.app.network.DebugLog
import com.playerbrowser.app.network.LinkNewTabSwitch
import com.playerbrowser.app.network.SniBypassClient
import com.playerbrowser.app.network.UrlRecovery
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.playerbrowser.app.web.IframeScriptInjector
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
}

@SuppressLint("SetJavaScriptEnabled")
fun buildBrowserWebView(context: Context, callbacks: WebViewCallbacks): BrowserWebViewState {
    // "이어보기" bridge — keyed by the page URL kept in sync via the WebViewClient.
    val resumeBridge = ResumeBridge(context.applicationContext)
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
            // Use Chrome's default UA — appending a custom suffix triggered anti-bot
            // rules on some sites and caused ERR_CONNECTION_RESET.
        }
        CookieManager.getInstance().also { cm ->
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
        }
        addJavascriptInterface(resumeBridge, "PBResume")
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
                if (LinkNewTabSwitch.enabled &&
                    request.isForMainFrame &&
                    request.hasGesture() &&
                    !request.isRedirect
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
                // Ad blocker first — cheapest check, returns an empty 204
                // before we waste cycles on sniffer / SNI / iframe injection.
                AdBlocker.intercept(request)?.let { return it }
                VideoStreamSniffer.observe(request, view?.tag as? String)
                val bypassed = SniBypassClient.intercept(request)
                return IframeScriptInjector.process(request, bypassed)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Track host so the sniffer attributes captured stream URLs to
                // the right page even when subresources come from CDNs.
                view?.tag = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                resumeBridge.currentUrl = url
                url?.let { callbacks.onStarted(it) }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) resumeBridge.currentUrl = url
                view?.evaluateJavascript(gestureScript, null)
                if (AdBlockSwitch.enabled) {
                    view?.evaluateJavascript(AdBlocker.HIDE_CSS_JS, null)
                }
                if (CookieBannerSwitch.enabled) {
                    view?.evaluateJavascript(CookieBannerKiller.SCRIPT, null)
                }
                if (view != null && url != null) {
                    callbacks.onFinished(
                        url = url,
                        title = view.title.orEmpty(),
                        canGoBack = view.canGoBack(),
                        canGoForward = view.canGoForward()
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
    }
    return BrowserWebViewState(webView, callbacks)
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
        webView.evaluateJavascript(DETECT_VIDEO_ORIENTATION_JS) { result ->
            if (customView == null) return@evaluateJavascript
            val orientation = when (result?.trim('"')) {
                "port" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                "land" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            activity.requestedOrientation = orientation
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
              return 'land';
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
    private val scrubThresholdPx = 20f * resources.displayMetrics.density
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val maxSwipeMs = 800L

    private var startX = 0f
    private var startY = 0f
    private var startT = 0L
    private var moved = false
    private var maxPointers = 1
    private var scrubbing = false
    private var lastScrubFireMs = 0L

    private var vbAdjust: VbMode? = null
    private var vbStartValue: Float = 0f
    private var lastVbFireMs: Long = 0L

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val maxVolume: Int by lazy {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
    }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        // Single tap (confirmed not a double-tap) → play/pause. We own taps now
        // (see dispatchTouchEvent: tap UPs are cancelled before reaching the
        // WebView), so this is the sole play/pause toggle for a lone tap.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (scrubbing || vbAdjust != null) return false
            // Element-aware tap: forward to the site's overlay control (toolbar
            // play/⏩/expand…) if one is under the finger, else toggle play. We
            // pass 0..1 ratios so the JS side can map to CSS coords regardless of
            // device pixel ratio. (The pass-through UP was cancelled in
            // dispatchTouchEvent, so native won't also act on this tap.)
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val xr = (e.x / w).coerceIn(0f, 1f)
            val yr = (e.y / h).coerceIn(0f, 1f)
            webView.evaluateJavascript(
                "window.__pb && window.__pb.fsTap && window.__pb.fsTap($xr, $yr);",
                null
            )
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scrubbing || vbAdjust != null) return false
            // Side-aware: left third → -10s, right third → +10s, middle → play/pause.
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val ratio = (e.x / w).coerceIn(0f, 1f)
            val js = when {
                ratio < 0.35f -> "window.__pb && window.__pb.seek && window.__pb.seek(-$SEEK_SECONDS);"
                ratio > 0.65f -> "window.__pb && window.__pb.seek && window.__pb.seek($SEEK_SECONDS);"
                else -> "window.__pb && window.__pb.fsTap && window.__pb.fsTap($ratio, ${(e.y / h).coerceIn(0f, 1f)});"
            }
            webView.evaluateJavascript(js, null)
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Single gesture source in native fullscreen. We FULLY consume every
        // touch and never forward raw touch events to the child WebView /
        // native <video>. Otherwise the site's own player also receives the
        // touches and runs its own double-tap / scrub, colliding with ours —
        // and because each player detects gestures differently (click vs
        // touchstart vs pointer), the result was inconsistent across sites
        // (double seek, surprise fullscreen toggle, etc). All intent is driven
        // through the window.__pb.* hooks: taps/double-taps via the detector
        // below, scrub/volume/brightness/switchVideo via the branches here, and
        // taps on the site's overlay controls via fsTap's synthetic-click
        // forwarding (so control buttons still work without raw touch).
        detector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                startT = System.currentTimeMillis()
                moved = false
                maxPointers = 1
                scrubbing = false
                vbAdjust = null
                webView.evaluateJavascript(
                    "window.__pb && window.__pb.scrubStart && window.__pb.scrubStart(${width.coerceAtLeast(1)});",
                    null
                )
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount > maxPointers) maxPointers = ev.pointerCount
                if (scrubbing) {
                    // Second finger landed mid-scrub → cancel scrubbing so a
                    // 2-finger switchVideo swipe can still be recognized.
                    scrubbing = false
                    webView.evaluateJavascript(
                        "window.__pb && window.__pb.scrubEnd && window.__pb.scrubEnd();",
                        null
                    )
                }
                if (vbAdjust != null) {
                    vbAdjust = null
                    webView.evaluateJavascript(
                        "window.__pb && window.__pb.hideVbOverlay && window.__pb.hideVbOverlay();",
                        null
                    )
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                if (!moved &&
                    (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)
                ) {
                    moved = true
                }
                if (maxPointers >= 2) return true // 2-finger swipe → switchVideo on UP; don't forward
                // Mode selection — first qualifying motion wins, scrubbing and
                // vbAdjust are mutually exclusive.
                if (!scrubbing && vbAdjust == null) {
                    if (abs(dx) > scrubThresholdPx && abs(dx) > abs(dy)) {
                        scrubbing = true
                    } else if (abs(dy) > scrubThresholdPx && abs(dy) > abs(dx)) {
                        val w = width.coerceAtLeast(1)
                        val mode = if (startX / w < 0.5f) VbMode.Brightness else VbMode.Volume
                        vbAdjust = mode
                        vbStartValue = when (mode) {
                            VbMode.Volume -> currentVolumeIndex().toFloat()
                            VbMode.Brightness -> currentBrightnessRatio()
                        }
                    }
                }
                if (scrubbing) {
                    val now = System.currentTimeMillis()
                    if (now - lastScrubFireMs >= 16) {
                        lastScrubFireMs = now
                        webView.evaluateJavascript(
                            "window.__pb && window.__pb.scrubBy && window.__pb.scrubBy(${dx.toInt()});",
                            null
                        )
                    }
                } else {
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
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                val dt = System.currentTimeMillis() - startT
                if (scrubbing) {
                    // Final scrub position then end.
                    webView.evaluateJavascript(
                        "window.__pb && window.__pb.scrubBy && window.__pb.scrubBy(${dx.toInt()});" +
                            "window.__pb && window.__pb.scrubEnd && window.__pb.scrubEnd();",
                        null
                    )
                    scrubbing = false
                } else if (vbAdjust != null) {
                    vbAdjust = null
                    webView.evaluateJavascript(
                        "window.__pb && window.__pb.hideVbOverlay && window.__pb.hideVbOverlay();",
                        null
                    )
                } else if (maxPointers >= 2 && moved && dt <= maxSwipeMs &&
                    abs(dx) >= swipeThresholdPx && abs(dx) > abs(dy)
                ) {
                    val dir = if (dx > 0) -1 else 1
                    webView.evaluateJavascript(
                        "window.__pb && window.__pb.switchVideo && window.__pb.switchVideo($dir);",
                        null
                    )
                }
                // A lone tap is handled by the GestureDetector
                // (onSingleTapConfirmed / onDoubleTap). Nothing to forward here —
                // we consume everything (return true below), so the native
                // <video> never sees the tap and can't double-toggle play/pause.
            }
            MotionEvent.ACTION_CANCEL -> {
                if (scrubbing) {
                    scrubbing = false
                    webView.evaluateJavascript(
                        "window.__pb && window.__pb.scrubEnd && window.__pb.scrubEnd();",
                        null
                    )
                }
                if (vbAdjust != null) {
                    vbAdjust = null
                    webView.evaluateJavascript(
                        "window.__pb && window.__pb.hideVbOverlay && window.__pb.hideVbOverlay();",
                        null
                    )
                }
            }
        }
        // Fully consume — the child WebView / native <video> sees no raw touch,
        // so this frame is the sole gesture source for every site's player.
        return true
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

    companion object {
        private const val SEEK_SECONDS = 10
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
