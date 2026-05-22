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
import com.playerbrowser.app.cast.VideoStreamSniffer
import com.playerbrowser.app.network.SniBypassClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.playerbrowser.app.web.IframeScriptInjector
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
}

@SuppressLint("SetJavaScriptEnabled")
fun buildBrowserWebView(context: Context, callbacks: WebViewCallbacks): BrowserWebViewState {
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
        val gestureScript = WebAssetLoader.gestureScript(context)
        IframeScriptInjector.setScript(gestureScript)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return UrlIntentRouter.route(view?.context ?: context, uri, callbacks)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                VideoStreamSniffer.observe(request, view?.tag as? String)
                val bypassed = SniBypassClient.intercept(request)
                return IframeScriptInjector.process(request, bypassed)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Track host so the sniffer attributes captured stream URLs to
                // the right page even when subresources come from CDNs.
                view?.tag = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                url?.let { callbacks.onStarted(it) }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(gestureScript, null)
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
                val html = ErrorPage.build(failingUrl, code, desc)
                view.loadDataWithBaseURL(failingUrl, html, "text/html", "UTF-8", failingUrl)
            }
        }
        webChromeClient = FullscreenAwareChromeClient(this)
    }
    return BrowserWebViewState(webView, callbacks)
}

private class FullscreenAwareChromeClient(
    private val webView: WebView
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
        // Open popups in the same WebView instead of dropping them.
        if (view == null || resultMsg == null) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = view
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
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scrubbing || vbAdjust != null) return false
            // Side-aware: left third → -10s, right third → +10s, middle → play/pause.
            val w = width.coerceAtLeast(1)
            val ratio = (e.x / w).coerceIn(0f, 1f)
            val js = when {
                ratio < 0.35f -> "window.__pb && window.__pb.seek && window.__pb.seek(-$SEEK_SECONDS);"
                ratio > 0.65f -> "window.__pb && window.__pb.seek && window.__pb.seek($SEEK_SECONDS);"
                else -> "window.__pb && window.__pb.togglePlay && window.__pb.togglePlay();"
            }
            webView.evaluateJavascript(js, null)
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
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
                if (maxPointers >= 2) return super.dispatchTouchEvent(ev)
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
                // Single tap and double-tap are handled by GestureDetector above.
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
        return super.dispatchTouchEvent(ev)
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
