package com.playerbrowser.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
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
import com.playerbrowser.app.network.SniBypassClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
                return SniBypassClient.intercept(request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
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

    private val swipeThresholdPx = 40f * resources.displayMetrics.density
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val maxSwipeMs = 800L

    private var startX = 0f
    private var startY = 0f
    private var startT = 0L
    private var moved = false
    private var maxPointers = 1
    private var swipeHandled = false

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            webView.evaluateJavascript(
                "window.__pb && window.__pb.togglePlay && window.__pb.togglePlay();",
                null
            )
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
                swipeHandled = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount > maxPointers) maxPointers = ev.pointerCount
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved &&
                    (abs(ev.x - startX) > touchSlopPx || abs(ev.y - startY) > touchSlopPx)
                ) {
                    moved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                val dt = System.currentTimeMillis() - startT
                if (!swipeHandled && moved && dt <= maxSwipeMs &&
                    abs(dx) >= swipeThresholdPx && abs(dx) > abs(dy)
                ) {
                    swipeHandled = true
                    val js = if (maxPointers >= 2) {
                        val dir = if (dx > 0) -1 else 1
                        "window.__pb && window.__pb.switchVideo && window.__pb.switchVideo($dir);"
                    } else {
                        val delta = if (dx > 0) SEEK_SECONDS else -SEEK_SECONDS
                        "window.__pb && window.__pb.seek && window.__pb.seek($delta);"
                    }
                    webView.evaluateJavascript(js, null)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
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
