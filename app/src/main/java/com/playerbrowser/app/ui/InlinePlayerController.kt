package com.playerbrowser.app.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.playerbrowser.app.network.InlinePlayerSwitch
import com.playerbrowser.app.web.InlineRect
import kotlin.math.abs

/**
 * A `<video>` reported by the page (via `PBInline`) as playing, waiting for
 * BrowserScreen to decide whether to lay our player over it.
 *
 * [manual] = the user picked "여기서 우리 플레이어로 재생" from the long-press
 * menu, which bypasses the setting and reports "no stream" instead of
 * silently waiting. [createdAt] (uptime ms) lets BrowserScreen drop an
 * automatic request that has waited too long for a stream to be sniffed —
 * otherwise a video that started minutes ago would still get taken over the
 * moment some unrelated `.mp4` request is seen.
 */
data class InlinePlayRequest(
    val tabId: String,
    val videoId: String,
    val domSrc: String,
    val positionSec: Double,
    val rect: InlineRect,
    val manual: Boolean,
    val createdAt: Long
)

/**
 * Snapshot state for the in-place player (v1.3.89), owned by RootNavigation
 * so it outlives BrowserScreen.
 *
 * Why not a `remember` inside BrowserScreen: the WebView callbacks are
 * created once per tab in `webStates.getOrPut` and survive navigation
 * (Settings → back), while any state remembered in BrowserScreen is
 * recreated — the callbacks would then write into an orphaned MutableState
 * and the new composition would never see a request. Holding the state here
 * keeps callbacks and UI pointed at the same object for the process lifetime.
 *
 * All methods are main-thread only (the bridge marshals there).
 */
class InlinePlayerController {
    /** Latest play report awaiting resolution, or null. */
    var request by mutableStateOf<InlinePlayRequest?>(null)
        private set

    /** The overlay currently shown (site video taken), or null. */
    var session by mutableStateOf<InlineSession?>(null)
        private set

    /** Live box of the taken video, device px relative to the WebView. */
    var rect by mutableStateOf<InlineRect?>(null)
        private set

    /**
     * Video long-press → external player request (DOM src). Moved here from
     * BrowserScreen for the same lifetime reason as above.
     */
    var externalPlayRequest by mutableStateOf<String?>(null)

    /**
     * Called when the controller ends a session on its own (video box became
     * unusable) so the owner can tell the page to release the site video.
     * BrowserScreen installs it; null before that means nothing to notify.
     */
    var releaseHandler: ((InlineSession) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var pendingRect: InlineRect? = null
    private var lastRectAppliedAt = 0L
    private val flushRect = Runnable { applyPendingRect() }
    private val unusableGrace = Runnable { endUnusable() }
    private var unusableArmed = false

    fun reportPlay(
        tabId: String,
        videoId: String,
        domSrc: String,
        positionSec: Double,
        rect: InlineRect,
        manual: Boolean
    ) {
        // The setting is the gate for automatic take-over; a manual pick
        // from the long-press menu always goes through.
        if (!manual && !InlinePlayerSwitch.enabled) return
        val s = session
        // Already overlaid on this very video: the site retrying play() is
        // handled by JS (kept muted+paused); nothing to resolve again.
        if (s != null && s.tabId == tabId && s.videoId == videoId && !manual) return
        val prev = request
        // The page re-reports the same video on every play() (site retries,
        // autoplay loops): keep the original deadline so a stale automatic
        // request cannot renew itself forever.
        val createdAt = if (prev != null && !prev.manual && !manual &&
            prev.tabId == tabId && prev.videoId == videoId
        ) prev.createdAt else SystemClock.uptimeMillis()
        request = InlinePlayRequest(tabId, videoId, domSrc, positionSec, rect, manual, createdAt)
    }

    /**
     * Box update from the page's rAF loop. Sub-2px jitter is ignored and
     * bursts are coalesced to one snapshot write per ~frame so a scrolling
     * page does not schedule a re-layout for every reported pixel.
     */
    fun reportRect(tabId: String, videoId: String, rect: InlineRect) {
        val s = session ?: return
        if (s.tabId != tabId || s.videoId != videoId) return
        if (!rect.isUsable) {
            // Collapsed to nothing (display:none, scrolled into a 0-height
            // container, …): give the page a moment — a layout thrash often
            // reports a zero box for one frame — then hand the video back.
            if (!unusableArmed) {
                unusableArmed = true
                main.postDelayed(unusableGrace, UNUSABLE_GRACE_MS)
            }
            return
        }
        if (unusableArmed) {
            unusableArmed = false
            main.removeCallbacks(unusableGrace)
        }
        val applied = pendingRect ?: this.rect
        if (applied != null && sameWithin(applied, rect, RECT_THRESHOLD_PX)) return
        pendingRect = rect
        val since = SystemClock.uptimeMillis() - lastRectAppliedAt
        if (since >= RECT_COALESCE_MS) {
            applyPendingRect()
        } else {
            main.removeCallbacks(flushRect)
            main.postDelayed(flushRect, RECT_COALESCE_MS - since)
        }
    }

    private fun applyPendingRect() {
        main.removeCallbacks(flushRect)
        val r = pendingRect ?: return
        pendingRect = null
        if (session == null) return
        lastRectAppliedAt = SystemClock.uptimeMillis()
        if (this.rect != r) this.rect = r
    }

    private fun endUnusable() {
        unusableArmed = false
        val s = end() ?: return
        releaseHandler?.invoke(s)
    }

    /** The taken video left the DOM: drop the overlay (JS already stopped tracking). */
    fun reportGone(tabId: String, videoId: String) {
        val s = session ?: return
        if (s.tabId == tabId && s.videoId == videoId) end()
    }

    fun consumeRequest() { request = null }

    fun start(session: InlineSession, rect: InlineRect) {
        clearTimers()
        this.session = session
        this.rect = rect
        lastRectAppliedAt = SystemClock.uptimeMillis()
        request = null
    }

    /** Clears the overlay and returns what was showing (for the JS release). */
    fun end(): InlineSession? {
        clearTimers()
        val s = session
        session = null
        rect = null
        return s
    }

    /** Tab navigated away or closed: forget anything belonging to it. */
    fun dropTab(tabId: String) {
        if (request?.tabId == tabId) request = null
        if (session?.tabId == tabId) end()
    }

    private fun clearTimers() {
        main.removeCallbacks(flushRect)
        main.removeCallbacks(unusableGrace)
        pendingRect = null
        unusableArmed = false
    }

    private fun sameWithin(a: InlineRect, b: InlineRect, px: Int): Boolean =
        abs(a.left - b.left) < px && abs(a.top - b.top) < px &&
            abs(a.width - b.width) < px && abs(a.height - b.height) < px

    private companion object {
        const val RECT_THRESHOLD_PX = 2
        const val RECT_COALESCE_MS = 16L
        const val UNUSABLE_GRACE_MS = 400L
    }
}
