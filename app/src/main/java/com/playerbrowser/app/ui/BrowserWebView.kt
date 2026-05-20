package com.playerbrowser.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@apply, true)
        }
        val gestureScript = WebAssetLoader.gestureScript(context)
        webViewClient = object : WebViewClient() {
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
        webChromeClient = object : WebChromeClient() {
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
        }
    }
    return BrowserWebViewState(webView, callbacks)
}

@Composable
fun BrowserWebViewHost(
    state: BrowserWebViewState,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { state.webView }
    )
}
