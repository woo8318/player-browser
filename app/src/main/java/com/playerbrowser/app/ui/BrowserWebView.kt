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
import kotlin.math.max
import kotlin.math.roundToInt
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
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
import com.playerbrowser.app.web.ElementHider
import com.playerbrowser.app.web.IframeScriptInjector
import com.playerbrowser.app.web.ImageOrderFixer
import com.playerbrowser.app.web.InlinePlayerBridge
import com.playerbrowser.app.web.InlineRect
import com.playerbrowser.app.web.PlayerBridge
import com.playerbrowser.app.web.ResumeBridge
import com.playerbrowser.app.web.VisitedLinkMarker
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
    // In-place player (v1.3.89): a <video> started playing / its box moved /
    // it left the DOM. Rects are device px relative to the WebView.
    fun onInlineVideoPlay(id: String, domSrc: String, positionSec: Double, rect: InlineRect, manual: Boolean) {}
    fun onInlineVideoRect(id: String, rect: InlineRect) {}
    fun onInlineVideoGone(id: String) {}
}

@SuppressLint("SetJavaScriptEnabled")
fun buildBrowserWebView(context: Context, callbacks: WebViewCallbacks): BrowserWebViewState {
    // "이어보기" bridge — keyed by the page URL kept in sync via the WebViewClient.
    val resumeBridge = ResumeBridge(context.applicationContext)
    val playerBridge = PlayerBridge { src -> callbacks.onPlayVideoExternally(src) }
    val inlineBridge = InlinePlayerBridge(object : InlinePlayerBridge.Listener {
        override fun onPlay(id: String, domSrc: String, positionSec: Double, rect: InlineRect, manual: Boolean) =
            callbacks.onInlineVideoPlay(id, domSrc, positionSec, rect, manual)
        override fun onRect(id: String, rect: InlineRect) = callbacks.onInlineVideoRect(id, rect)
        override fun onGone(id: String) = callbacks.onInlineVideoGone(id)
    })
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
            // Chrome/Opera 의 팝업 차단과 같은 규칙 — 사용자 제스처 없는 window.open 은
            // Blink 가 그 자리에서 막는다(null 반환). 예전 true 는 광고 팝언더까지
            // 전부 onCreateWindow 로 올려보내 새 탭으로 열었다 (v1.3.84).
            javaScriptCanOpenWindowsAutomatically = false
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
        // `window.PBInline` — in-place player rect/play reports (see InlinePlayerBridge).
        addJavascriptInterface(inlineBridge, "PBInline")
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
                val pageHost = view?.tag as? String
                if (ChallengeDetector.isQuarantinedHost(request.url?.host)) {
                    VideoStreamSniffer.observe(request, pageHost)
                    return null
                }
                // 격리 **페이지**의 서브리소스(다른 호스트의 플레이어 iframe·CDN 포함)도
                // SNI 우회·iframe 재요청에서 손을 뗀다 (v1.3.88). 메인 프레임은 제외 —
                // 격리 사이트에서 다른 사이트로 넘어가는 첫 요청은 아직 이전 페이지의
                // 태그를 달고 오므로, 그건 요청 호스트 기준(위)으로만 판정한다.
                // 광고차단은 그대로 둔다(광고 호스트 목록은 실제 광고망뿐이다).
                if (!request.isForMainFrame && ChallengeDetector.isQuarantinedHost(pageHost)) {
                    VideoStreamSniffer.observe(request, pageHost)
                    AdBlocker.intercept(request)?.let { return it }
                    ChallengeDetector.noteSkipped("page-격리", request.url)
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
                // 주소창에서 이동하면 View 포커스가 어디에도 없어 문서가 unfocused 로
                // 시작한다 — document.hasFocus()=false (v1.3.84).
                view?.focusPageUnlessTyping()
                // `window.PBResume` / `window.PBPlayer` 같은 네이티브 브리지는
                // 페이지가 window를 훑으면 그대로 보인다 — 안티봇이 자동화
                // 브라우저로 판정하는 대표적 신호다. 챌린지 호스트에선 떼어낸다
                // (챌린지 페이지엔 비디오도 이어보기도 없다).
                if (view != null) {
                    runCatching {
                        if (ChallengeDetector.isChallengeActive(host)) {
                            view.removeJavascriptInterface("PBResume")
                            view.removeJavascriptInterface("PBPlayer")
                            view.removeJavascriptInterface("PBInline")
                        } else {
                            view.addJavascriptInterface(resumeBridge, "PBResume")
                            view.addJavascriptInterface(playerBridge, "PBPlayer")
                            view.addJavascriptInterface(inlineBridge, "PBInline")
                        }
                    }
                }
                resumeBridge.currentUrl = url
                playerBridge.pageHost = host
                url?.let { callbacks.onStarted(it) }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) resumeBridge.currentUrl = url
                // 챌린지 페이지엔 아무것도 주입하지 않는다. 제스처 스크립트는
                // document에 캡처 리스너를 걸고, 광고차단 CSS는 위젯을 가릴 수
                // 있고, 쿠키배너 킬러는 버튼을 눌러댄다 — 안티봇 입장에선 전부
                // 자동화 신호이고 챌린지엔 어차피 쓸모가 없다.
                val finishedHost = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                // 챌린지 창(60초)은 아래 probe/probeTitle 이 **이번** 로드를 판정한
                // 뒤에야 열린다 — 콜드 스타트의 1라운드 챌린지 문서는 창이 비어
                // 있어 여기서 걸러지지 않았다. 제목도 같이 본다(Kotlin 신호라 JS
                // 없음). fetch/XHR 를 감싸는 스크립트가 들어간 뒤로는 1라운드도
                // 안 된다(v1.3.91).
                val onChallenge = ChallengeDetector.isChallengeActive(finishedHost) ||
                    ChallengeDetector.isChallengeTitle(view?.title)
                playerBridge.challengePage = onChallenge
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
                    // 도메인 숫자가 바뀌어도 읽었던 글 링크를 표시 (v1.3.83).
                    view?.let { VisitedLinkMarker.apply(it, url) }
                    // 자동 정렬을 켠 사이트면 웹툰 이미지를 파일명 번호순으로 (v1.3.99).
                    view?.let { ImageOrderFixer.applyIfRemembered(it, url) }
                    // 사용자가 이 사이트에서 숨긴 요소를 계속 숨김 (v1.3.100).
                    view?.let { ElementHider.apply(it, url) }
                }
                // "사람인지 확인" 위젯이 떠 있는지 살펴 디버그 로그에 기록
                // (어떤 사이트가 어떤 캡차를 쓰는지 / 루프에 빠졌는지 추적용).
                // 수정 사다리 2/4 (v1.3.87): 격리 호스트·챌린지 창이 열린 호스트·
                // 제목이 이미 챌린지인 페이지에서는 `evaluateJavascript` 프로브를
                // 돌리지 않고 제목만으로 판정한다 — 챌린지 페이지 위에서 우리
                // 스크립트가 실행되는 것 자체를 없앤다.
                val title = view?.title
                if (onChallenge ||
                    ChallengeDetector.isQuarantinedHost(finishedHost) ||
                    ChallengeDetector.isChallengeTitle(title)
                ) {
                    ChallengeDetector.probeTitle(view, url, title)
                } else {
                    ChallengeDetector.probe(view, url)
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
                if (request == null || errorResponse == null) return
                if (request.isForMainFrame) {
                    ChallengeDetector.noteHttpError(
                        request.url,
                        errorResponse.statusCode,
                        errorResponse.responseHeaders
                    )
                    return
                }
                // 격리 페이지의 서브리소스만 — 보통 사이트의 404 잡음까지 남기지 않는다.
                val pageHost = view?.tag as? String
                if (ChallengeDetector.isQuarantinedHost(pageHost)) {
                    ChallengeDetector.noteSubresourceHttpError(
                        pageHost, request.url, errorResponse.statusCode, errorResponse.responseHeaders
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

/**
 * Chromium 은 View 포커스가 없으면 문서를 unfocused 로 둔다 — `document.hasFocus()` 가
 * false 이고 `focus` 이벤트가 안 오며 autofocus 입력에 키보드가 안 뜬다. 지금까지는
 * 사용자가 페이지를 한 번 탭해야 비로소 풀렸다. 주소창(텍스트 편집기)에 입력 중이면
 * 뺏지 않는다 (v1.3.84).
 */
internal fun WebView.focusPageUnlessTyping() {
    // detached View 의 rootView 는 자기 자신이라 findFocus 가 주소창을 못 본다 —
    // 붙기 전엔 requestFocus 도 무효이므로 그냥 나간다 (AndroidView update 가 붙인 뒤 다시 부른다).
    if (!isAttachedToWindow) return
    if (hasFocus()) return
    // Compose 텍스트필드는 onCheckIsTextEditor 를 안 올릴 수 있어 주소창 포커스는
    // AddressBarFocus 플래그로 확정한다.
    if (AddressBarFocus.typing) return
    val focused = rootView?.findFocus()
    if (focused != null && focused !== this && focused.onCheckIsTextEditor()) return
    requestFocus()
}

private class FullscreenAwareChromeClient(
    private val webView: WebView,
    private val callbacks: WebViewCallbacks
) : WebChromeClient() {
    private var customView: View? = null
    private var customViewContainer: ViewGroup? = null
    private var customViewCallback: CustomViewCallback? = null
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var savedCutoutMode: Int? = null

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
                // 광고 팝언더 — 오페라의 광고 차단이 조용히 삼키는 것. 목록에 있는
                // 광고 네트워크로 가는 팝업은 탭을 만들지 않는다 (v1.3.84).
                val host = runCatching { Uri.parse(url).host }.getOrNull()
                if (AdBlockSwitch.enabled && AdBlocker.isBlockedHost(host)) {
                    DebugLog.d("Popup", "광고 팝업 차단 host=$host gesture=$isUserGesture")
                } else {
                    DebugLog.d("Popup", "팝업 → 새 탭 host=$host gesture=$isUserGesture")
                    callbacks.onOpenInNewTab(url)
                }
                v?.stopLoading()
                // 콜백 안에서 destroy 하면 Chromium 이 아직 이 뷰를 참조 중일 수 있다.
                v?.post { v.destroy() }
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

    // Chrome/Opera 는 EME(Widevine DRM) 요청을 묻지 않고 허용한다. WebView 는 이 콜백이
    // 없으면 **전부 거부** — navigator.requestMediaKeySystemAccess('com.widevine.alpha') 가
    // NotSupportedError 로 떨어져 DRM 영상이 우리 브라우저에서만 검은 화면이었다.
    // 카메라/마이크는 매니페스트에 권한 자체가 없어 허용해도 캡처가 실패하므로 거부가
    // 정직하고, MIDI sysex 도 쓸 데가 없다 (v1.3.84).
    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        val resources = request.resources.orEmpty()
        val granted = resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
        DebugLog.d(
            "Permission",
            "${request.origin} 요청 [${resources.joinToString()}] → " +
                if (granted.isEmpty()) "거부" else "DRM 허용"
        )
        runCatching {
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        }.onFailure { e ->
            DebugLog.d("Permission", "grant 실패 → deny: ${e.javaClass.simpleName}")
            runCatching { request.deny() }
        }
    }

    // 기본 구현은 아무것도 안 해서 getCurrentPosition 콜백이 영영 오지 않는다 — 위치
    // 권한이 없는 앱이니 Chrome 에서 "차단" 을 누른 것처럼 즉시 거부해 사이트가 다음
    // 단계로 넘어가게 한다 (v1.3.84).
    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        callback?.invoke(origin.orEmpty(), false, false)
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
        // Index 0: the frame already holds its gesture HUD, which must stay on top.
        container.addView(
            view,
            0,
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

        // With the default cutout mode Android letterboxes a bars-hidden window
        // away from the camera cutout — a black strip on the camera side in
        // landscape, or along the top in portrait — so fullscreen never reached
        // the edge. Let the window extend into cutouts on the short edges while
        // fullscreen lasts (v1.3.93).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = activity.window.attributes
            savedCutoutMode = lp.layoutInDisplayCutoutMode
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            activity.window.attributes = lp
        }

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
            // The CustomView may be reused by the next fullscreen — never let it
            // carry this session's zoom (v1.3.96).
            (container as? GestureCapturingFrame)?.resetZoom()
            if (container != null) decor.removeView(container)
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
            WindowInsetsControllerCompat(activity.window, decor)
                .show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = savedOrientation
            // Restore system-default brightness in case the vertical-drag
            // gesture overrode it during fullscreen playback.
            val lp = activity.window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            val cutoutMode = savedCutoutMode
            if (cutoutMode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode = cutoutMode
            }
            activity.window.attributes = lp
        }
        savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        savedCutoutMode = null
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
 *
 * Feedback for the gestures this frame owns is drawn by [FullscreenGestureHud],
 * a child kept above the CustomView (v1.3.90). The page's DOM can't do it:
 * during native fullscreen only the fullscreen element's subtree is painted
 * and the CustomView covers the WebView anyway, so the JS overlays these
 * gestures used to drive were never visible — they worked blind.
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
    private val edgeTopBottomPx = EDGE_TOP_BOTTOM_DP * resources.displayMetrics.density
    private val edgeSidePx = EDGE_SIDE_DP * resources.displayMetrics.density

    private var fromEdge = false
    private var startX = 0f
    private var startY = 0f
    private var startT = 0L
    private var moved = false
    private var maxPointers = 1
    private var siteCancelled = false
    private var seekFired = false

    private var vbAdjust: VbMode? = null
    private var vbStartValue: Float = 0f

    // Pinch zoom (v1.3.96). Opt-in on top of the uncropped fit: pinch to zoom,
    // one finger pans while zoomed, double-tap or leaving fullscreen resets.
    // The target is the CustomView at index 0 — never the HUD, which is the
    // only child before onShowCustomView inserts it.
    private val zoom = FullscreenZoom(ZOOM_SLOP_DP * resources.displayMetrics.density) {
        getChildAt(0)?.takeIf { it !== hud }
    }
    private val doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private var zoomedAtDown = false
    private var zoomGesture = false
    private var panning = false
    private var panX = 0f
    private var panY = 0f
    private var swallowGesture = false
    private var lastTapUpT = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var shownZoomPct = -1

    // Native feedback for every gesture this frame owns. DOM overlays are
    // invisible during native fullscreen (only the fullscreen element's subtree
    // is painted, and the CustomView sits above the WebView anyway), so the
    // JS-side showVbOverlay/toast never reached the screen — the gesture worked
    // blind. Added at construction so the CustomView (inserted at index 0 by
    // onShowCustomView) always sits underneath it.
    private val hud = FullscreenGestureHud(context).also {
        addView(
            it,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

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
        // A gesture that began on a system edge belongs to the system (pulling
        // the notification shade, back, home) — the app layers nothing on it
        // (v1.3.92). In immersive fullscreen that swipe is delivered to us too,
        // so it used to engage brightness/volume on its way down.
        if (fromEdge && ev.actionMasked != MotionEvent.ACTION_DOWN) {
            return super.dispatchTouchEvent(ev)
        }
        // The second tap of a reset double-tap: the site never saw its DOWN, so
        // none of the rest goes to it either.
        if (swallowGesture && ev.actionMasked != MotionEvent.ACTION_DOWN) {
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                swallowGesture = false
            }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fromEdge = startsAtSystemEdge(ev.x, ev.y)
                startX = ev.x
                startY = ev.y
                startT = System.currentTimeMillis()
                moved = false
                maxPointers = 1
                siteCancelled = false
                vbAdjust = null
                seekFired = false
                swallowGesture = false
                zoomGesture = false
                panning = false
                zoomedAtDown = zoom.isZoomed
                zoom.beginGesture()
                // An edge gesture never reaches our UP, so it must not leave the
                // tap before it armed for a reset double-tap.
                if (fromEdge) lastTapUpT = 0L
                if (!fromEdge && isResetDoubleTap(ev)) {
                    lastTapUpT = 0L
                    swallowGesture = true
                    zoom.reset()
                    shownZoomPct = -1
                    hud.showMessage("원래 크기")
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount > maxPointers) maxPointers = ev.pointerCount
                // Take the new finger into the zoom's baseline (spread and focal
                // point), so adding it doesn't read as a sudden pinch.
                zoom.pointersChanged()
                if (!seekFired) zoom.onMultiTouchMove(ev, immediate = false)
                if (maxPointers >= 2 && !siteCancelled) {
                    // Two fingers → reserve for a switchVideo swipe; stop the
                    // site tracking the first finger so it doesn't also act.
                    cancelChildren(ev)
                    siteCancelled = true
                }
                if (vbAdjust != null) {
                    vbAdjust = null
                    hud.hide()
                }
                return if (siteCancelled) true else super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // The lifted finger still shows in this event; the next MOVE
                // re-takes the baseline from the fingers that remain.
                zoom.pointersChanged()
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
                    // A changing finger spread is a pinch (v1.3.96); a steady
                    // spread moving sideways stays the 2-finger switchVideo swipe,
                    // decided on UP. Already zoomed → two fingers always mean
                    // zoom/pan. Consumed either way (site cancelled).
                    if (!seekFired && zoom.onMultiTouchMove(ev, immediate = zoomedAtDown)) {
                        zoomGesture = true
                        showZoomLevel()
                    }
                    return true
                }
                if (seekFired) return true
                // Zoomed → one finger pans the picture instead of seeking or
                // adjusting brightness/volume. A tap still reaches the site, and
                // so does a drag from the bottom control band (its scrubber).
                if (zoomedAtDown) {
                    if (startY >= height * SEEK_BAND_MAX_Y_RATIO) {
                        return super.dispatchTouchEvent(ev)
                    }
                    if (!panning && moved) {
                        panning = true
                        // From the touch-down point, so the picture tracks the
                        // finger rather than lagging by the slop distance.
                        panX = startX
                        panY = startY
                        if (!siteCancelled) {
                            cancelChildren(ev)
                            siteCancelled = true
                        }
                    }
                    if (panning) {
                        zoom.panBy(ev.x - panX, ev.y - panY)
                        panX = ev.x
                        panY = ev.y
                        return true
                    }
                    return super.dispatchTouchEvent(ev)
                }
                // 1-finger horizontal swipe → one discrete ±10s step per gesture
                // (v1.3.90). Discrete rather than proportional on purpose: when
                // the player sits in a cross-origin iframe we can't read its
                // current time, so a proportional HUD would be a guess. Not
                // engaged from the bottom control band, where a site's own
                // scrubber lives — that keeps working via the forwarded touches.
                if (vbAdjust == null &&
                    abs(dx) > swipeThresholdPx && abs(dx) > abs(dy) &&
                    startY < height * SEEK_BAND_MAX_Y_RATIO
                ) {
                    seekFired = true
                    if (!siteCancelled) {
                        cancelChildren(ev)
                        siteCancelled = true
                    }
                    fireSeek(if (dx > 0) SEEK_SEC else -SEEK_SEC)
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
                                hud.showLevel(FullscreenGestureHud.Level.Volume, newIdx / maxVol)
                            }
                        }
                        VbMode.Brightness -> {
                            val newRatio = (vbStartValue + deltaRatio).coerceIn(0f, 1f)
                            applyBrightness(newRatio)
                            hud.showLevel(FullscreenGestureHud.Level.Brightness, newRatio)
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
                // Only a clean single tap can start a reset double-tap.
                lastTapUpT = if (maxPointers == 1 && !moved) ev.eventTime else 0L
                lastTapX = ev.x
                lastTapY = ev.y
                if (vbAdjust != null) {
                    vbAdjust = null
                    hud.hide()
                    return true
                }
                if (seekFired) return true
                if (zoomGesture) {
                    finishZoomGesture()
                    return true
                }
                if (panning) return true
                if (maxPointers >= 2) {
                    if (!zoomedAtDown && moved && dt <= maxSwipeMs &&
                        abs(dx) >= swipeThresholdPx && abs(dx) > abs(dy)
                    ) {
                        fireSwitch(if (dx > 0) -1 else 1)
                    }
                    return true
                }
                // Plain tap / forwarded drag → let the site complete it (reveals
                // controls, toggles its own play/pause, etc).
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTapUpT = 0L
                if (vbAdjust != null) {
                    vbAdjust = null
                    hud.hide()
                }
                if (zoomGesture) finishZoomGesture()
                panning = false
                return super.dispatchTouchEvent(ev)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Back to the uncropped fit; called when fullscreen ends. */
    fun resetZoom() {
        zoom.reset()
        shownZoomPct = -1
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Rotation changes the picture's size — keep the pan inside the new bounds.
        if (changed) zoom.reclamp()
    }

    // Only while zoomed: unzoomed, both taps go to the site untouched (its own
    // double-tap seek keeps working). Tighter than the platform double-tap slop
    // so two quick taps on different site buttons don't count.
    private fun isResetDoubleTap(ev: MotionEvent): Boolean {
        val slop = touchSlopPx * 2f
        return zoom.isZoomed && lastTapUpT != 0L &&
            ev.eventTime - lastTapUpT <= doubleTapTimeoutMs &&
            abs(ev.x - lastTapX) < slop && abs(ev.y - lastTapY) < slop
    }

    private fun showZoomLevel() {
        val pct = (zoom.scale * 100f).roundToInt()
        if (pct == shownZoomPct) return
        shownZoomPct = pct
        hud.showMessage("확대 $pct%")
    }

    private fun finishZoomGesture() {
        zoomGesture = false
        shownZoomPct = -1
        if (zoom.settle()) {
            hud.showMessage("원래 크기")
        } else {
            showZoomLevel()
        }
    }

    // Fixed floors plus the device's own gesture regions where it reports them
    // (API 29+; the side insets follow the user's back-gesture sensitivity).
    private fun startsAtSystemEdge(x: Float, y: Float): Boolean {
        var top = edgeTopBottomPx
        var bottom = edgeTopBottomPx
        var left = edgeSidePx
        var right = edgeSidePx
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rootWindowInsets?.systemGestureInsets?.let { g ->
                top = max(top, g.top.toFloat())
                bottom = max(bottom, g.bottom.toFloat())
                left = max(left, g.left.toFloat())
                right = max(right, g.right.toFloat())
            }
        }
        return y < top || y > height - bottom || x < left || x > width - right
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

    // The JS hooks report what they did: 'seek' / 'switch' = acted on a video
    // in the top document, 'relay' = broadcast to child iframes (outcome
    // unknowable from here, so we show the intended action), anything else =
    // no video, or hooks not injected (challenge page). The HUD says what the
    // callback says rather than assuming.
    private fun fireSeek(deltaSec: Int) {
        val label = if (deltaSec > 0) "⏩ ${deltaSec}초" else "⏪ ${-deltaSec}초"
        webView.evaluateJavascript(
            "window.__pb && window.__pb.seek && window.__pb.seek($deltaSec);"
        ) { result ->
            DebugLog.d("FsGesture", "swipe seek $deltaSec → $result")
            hud.showMessage(if (result.isHookSuccess()) label else "시킹할 영상 없음")
        }
    }

    private fun fireSwitch(dir: Int) {
        val label = if (dir > 0) "다음 영상" else "이전 영상"
        webView.evaluateJavascript(
            "window.__pb && window.__pb.switchVideo && window.__pb.switchVideo($dir);"
        ) { result ->
            DebugLog.d("FsGesture", "2-finger switch $dir → $result")
            hud.showMessage(if (result.isHookSuccess()) label else "다른 영상 없음")
        }
    }

    // evaluateJavascript hands back a JSON literal: "\"seek\"" / "\"relay\""
    // on success, "\"noop\"" / "\"switch-none\"" when there was nothing to
    // act on, "null" when the hook chain short-circuited.
    private fun String?.isHookSuccess(): Boolean =
        this == "\"seek\"" || this == "\"switch\"" || this == "\"relay\""

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

    private companion object {
        const val SEEK_SEC = 10
        // Swipe-seek engages only above this fraction of the height; below it
        // is left to the site's control bar / scrubber.
        const val SEEK_BAND_MAX_Y_RATIO = 0.80f
        // Touches starting this close to an edge are left to the system.
        const val EDGE_TOP_BOTTOM_DP = 48f
        const val EDGE_SIDE_DP = 24f
        // Finger-spread change that turns two fingers into a pinch rather than
        // the video-switch swipe.
        const val ZOOM_SLOP_DP = 24f
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
                // 붙는 순간이 포커스를 줄 수 있는 첫 시점 — LaunchedEffect(activeTabId) 는
                // 아직 detached 라 requestFocus 가 무효다 (v1.3.84).
                wv.focusPageUnlessTyping()
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
