package com.playerbrowser.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.playerbrowser.app.network.ChallengeCookies
import com.playerbrowser.app.network.ChallengeDetector
import com.playerbrowser.app.network.DebugLog
import com.playerbrowser.app.network.EnvSpoofSwitch
import com.playerbrowser.app.network.UserAgentSpoof

/**
 * 순정 WebView 진단 창 (v1.3.85).
 *
 * v1.3.72 의 "순정 WebView 도 루프한다" 는 결론은 반쪽이었다 — 그 A/B 는
 * UA·헤더·JS 주입만 껐고, 앱의 `WebViewClient`(요청 가로채기 훅), 챌린지
 * 페이지 위에서 `evaluateJavascript` 로 도는 DOM 진단, 네이티브 브리지,
 * 그리고 배포 APK 가 debug 빌드라 항상 켜져 있던 WebView 원격 디버깅(v1.3.86
 * 에서 앱 전체에서 제거)은 그대로였다. 이 액티비티는 그 넷이 **전부 없는** WebView 로 같은 주소를
 * 열어 "앱 코드가 원인인가, WebView 엔진이 원인인가" 를 한 번에 가른다.
 *
 * 일부러 없는 것: `shouldInterceptRequest`, document-start 스크립트,
 * `addJavascriptInterface`, `WebChromeClient`, `evaluateJavascript`.
 * 있는 것: 로그만 남기는 `onPageStarted/onPageFinished/onReceivedHttpError`.
 *
 * UA 는 메인 WebView 와 같은 규칙으로 맞춘다 — `cf_clearance` 는 UA 에
 * 묶여 발급되고 `CookieManager` 는 앱 전역 공유이므로, 여기서 통과해 받은
 * 토큰이 돌아간 탭에서 그대로 쓰이려면 UA 가 같아야 한다.
 */
class BareWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var rounds = 0
    private var passedLogged = false
    // 메인 프레임 HTTP 에러(≥400)는 onPageFinished 보다 먼저 오므로 URL 과
    // 함께 붙들어 뒀다가 같은 URL 의 완료 로그에 붙인다.
    private var mainFrameError: Pair<String, String>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!url.startsWith("http", ignoreCase = true)) {
            finish()
            return
        }
        // v1.3.86 부터 앱 전체가 원격 디버깅을 켜지 않는다 — 여기서도 확인 사살.
        runCatching { WebView.setWebContentsDebuggingEnabled(false) }

        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(24, 12, 24, 12)
            maxLines = 3
        }
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.apply {
                // Cloudflare 문서의 필수 둘(JS, DOM storage) + DB. 그 외 설정은
                // WebView 기본값 그대로 — 여기서 뭔가를 "맞추기" 시작하면 더는
                // 순정 측정이 아니다.
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                if (EnvSpoofSwitch.enabled) {
                    userAgentString = UserAgentSpoof.chromeLike(userAgentString)
                    UserAgentSpoof.applyClientHints(this, userAgentString)
                    UserAgentSpoof.stripRequestedWithHeader(this)
                }
            }
            CookieManager.getInstance().also { cm ->
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
            }
            webViewClient = LoggingClient()
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(webView)
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        DebugLog.d(
            TAG,
            "열기: $url (위장=${EnvSpoofSwitch.enabled}, 원격 디버깅 off, " +
                "가로채기/주입/브리지/ChromeClient 없음) UA=${webView.settings.userAgentString}"
        )
        render(url, "")
        webView.loadUrl(url)
    }

    private fun render(url: String?, title: String) {
        val head = if (rounds == 0) "순정 WebView 진단" else "순정 WebView 진단 — 챌린지 ${rounds}회"
        status.text = "$head · ${title.take(40)}\n${url.orEmpty()}"
    }

    private inner class LoggingClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            DebugLog.d(TAG, "시작: $url")
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request?.isForMainFrame != true || errorResponse == null) return
            val mitigated = errorResponse.responseHeaders
                ?.entries?.firstOrNull { it.key.equals("cf-mitigated", ignoreCase = true) }?.value
            mainFrameError = request.url.toString() to
                "${errorResponse.statusCode} (cf-mitigated=$mitigated)"
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val title = view?.title.orEmpty()
            val host = runCatching { Uri.parse(url).host }.getOrNull()
            val mainStatus = mainFrameError
                ?.takeIf { it.first == url }?.second ?: "HTTP 에러 없음(2xx/3xx)"
            mainFrameError = null
            val detail = "$url title=\"${title.take(40)}\" 메인 응답=$mainStatus, " +
                ChallengeCookies.describe(host)
            when {
                ChallengeDetector.isChallengeTitle(title) -> {
                    rounds++
                    passedLogged = false
                    DebugLog.w(TAG, "챌린지 #$rounds: $detail")
                }
                rounds > 0 && !passedLogged -> {
                    passedLogged = true
                    DebugLog.d(TAG, "통과 — ${rounds}라운드 뒤: $detail")
                }
                else -> DebugLog.d(TAG, "완료: $detail")
            }
            render(url, title)
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            DebugLog.d(
                TAG,
                "닫음: 챌린지 ${rounds}회, 통과=$passedLogged, 마지막=${webView.url}"
            )
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
            runCatching { webView.destroy() }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BareWebView"
        private const val EXTRA_URL = "url"

        fun start(context: Context, url: String) {
            context.startActivity(
                Intent(context, BareWebViewActivity::class.java).putExtra(EXTRA_URL, url)
            )
        }
    }
}
