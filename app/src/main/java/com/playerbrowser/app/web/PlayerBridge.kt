package com.playerbrowser.app.web

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.playerbrowser.app.cast.VideoStreamSniffer
import com.playerbrowser.app.network.BodySniffSwitch
import com.playerbrowser.app.network.ChallengeDetector
import java.util.concurrent.atomic.AtomicInteger

/**
 * `window.PBPlayer` — bridge between [com.playerbrowser.app.assets video_gestures.js]
 * and the external Media3 player. When the user long-presses a `<video>`, the JS
 * reads that element's source URL and calls [openVideo]; the browser screen then
 * resolves it to a playable stream (direct URL verbatim, or a fallback to the
 * sniffed network stream for blob:/MSE videos) and launches the player.
 *
 * [openVideo] runs on the JS-bridge (binder) thread, so it hops to the main
 * thread before invoking the UI callback.
 */
class PlayerBridge(private val onOpenVideo: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    /** domSrc is the long-pressed video's currentSrc/<source> ("" if unresolved). */
    @JavascriptInterface
    fun openVideo(domSrc: String?) {
        val src = domSrc.orEmpty()
        main.post { onOpenVideo(src) }
    }

    // Page this WebView is showing, set by onPageStarted (like
    // ResumeBridge.currentUrl). Body-sniff candidates are filed under it rather
    // than the sniffer's global "last host", which belongs to whichever tab
    // loaded something last.
    @Volatile var pageHost: String? = null
        set(value) {
            field = value
            bodyReports.set(0)
            challengePage = false
        }

    // Set by onPageFinished when the finished document is a challenge (by
    // title, before the 60 s challenge window exists on round 1).
    @Volatile var challengePage: Boolean = false

    private val bodyReports = AtomicInteger(0)

    /**
     * Body sniff (v1.3.91): the page-side fetch/XHR wrapper saw a GET whose
     * response *starts like* a playlist/mp4/webm, whatever its URL said.
     * Page input end to end - any frame can call this directly, as often as it
     * likes - so the gate, the URL shape, the mime and a per-page budget are
     * all checked here. Thread-safe sink, so no main-thread hop.
     */
    @JavascriptInterface
    fun onStreamBody(url: String?, mime: String?) {
        if (!bodySniffEnabled()) return
        val host = pageHost ?: return
        val u = url ?: return
        val m = mime ?: return
        if (u.length > MAX_URL_LEN || m !in BODY_MIMES) return
        if (u.any { it.isWhitespace() || it.isISOControl() }) return
        val parsed = runCatching { Uri.parse(u) }.getOrNull() ?: return
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        if (parsed.host.isNullOrBlank()) return
        if (bodyReports.incrementAndGet() > MAX_REPORTS_PER_PAGE) return
        VideoStreamSniffer.observeContent(host, u, m)
    }

    /**
     * Every frame asks this before wrapping fetch/XHR, so switching the
     * setting off really removes the wrappers everywhere (from the next page).
     * Never on a challenge page or a quarantined site: patching fetch is the
     * kind of thing anti-bot JS looks for, and on those sites a failed check
     * costs the clearance token (v1.3.87).
     */
    @JavascriptInterface
    fun bodySniffEnabled(): Boolean =
        BodySniffSwitch.enabled &&
            !challengePage &&
            !ChallengeDetector.isChallengeActive(pageHost) &&
            !ChallengeDetector.isQuarantinedHost(pageHost)

    private companion object {
        const val MAX_URL_LEN = 4096
        // A real page reports a handful; a page flooding the bridge would
        // otherwise evict genuine candidates and recompose on every call.
        const val MAX_REPORTS_PER_PAGE = 8
        val BODY_MIMES = setOf("application/vnd.apple.mpegurl", "video/mp4", "video/webm")
    }
}
