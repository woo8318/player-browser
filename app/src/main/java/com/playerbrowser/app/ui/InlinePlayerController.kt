package com.playerbrowser.app.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.playerbrowser.app.network.DebugLog
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

    /**
     * Called after the controller ended a session on its own, with why
     * ("hidden" = box collapsed, "gone" = video left the DOM, "nav" = the
     * tab loaded a new document). An overlay that silently vanishes looks
     * exactly like "our player reverted", so BrowserScreen toasts the reason —
     * manual sessions since v1.3.95, automatic ones too since v1.3.97 (the
     * user saw the overlay appear, so its disappearance deserves a word).
     */
    var onAutoEnded: ((InlineSession, String) -> Unit)? = null

    /**
     * Manual play reports that reached the controller, counted before any
     * gate. BrowserScreen compares it across the long-press "pressed" command
     * to tell "the page answered" from "the frame never replied" (v1.3.95).
     */
    var manualReports = 0
        private set

    /**
     * `manual=true` comes from page JS, and any page can call `PBInline.onPlay`
     * with it — so it only counts inside the short window the long-press menu
     * opens with [armManual]. Outside it the report is treated as automatic
     * (setting gate applies) and cannot fire the manual toasts.
     */
    private var manualArmedUntil = 0L

    private val main = Handler(Looper.getMainLooper())
    private var pendingRect: InlineRect? = null
    private var lastRectAppliedAt = 0L
    private val flushRect = Runnable { applyPendingRect() }
    private val unusableGrace = Runnable { endUnusable() }
    private var unusableArmed = false

    /**
     * A take on a video that no longer exists (element swapped between the
     * play report and our `take`) gets no rect and no gone from JS — the
     * overlay would sit over nothing forever. A live take always reports a
     * rect on its first rAF tick, so silence means "not tracked".
     */
    private val noTrack = Runnable { endNoTrack() }

    /** Auto report for another video that arrived while a session was live. */
    private var deferred: InlinePlayRequest? = null

    /** Uptime of the latest park; only a recent one is worth promoting. */
    private var deferredAt = 0L

    /** Video ids already logged as parked this session (keeps the ring buffer readable). */
    private val ignoredLogged = HashSet<String>()

    /** "tabId|streamUrl" our player already failed on (see [noteFailed]). */
    private val failed = HashSet<String>()

    fun reportPlay(
        tabId: String,
        videoId: String,
        domSrc: String,
        positionSec: Double,
        rect: InlineRect,
        manualReport: Boolean
    ) {
        val manual = manualReport && takeManualArm()
        if (manual) manualReports++
        // The setting is the gate for automatic take-over; a manual pick
        // from the long-press menu always goes through.
        if (!manual && !InlinePlayerSwitch.enabled) return
        val s = session
        // Already overlaid on this very video: the site retrying play() is
        // handled by JS (kept muted+paused); nothing to resolve again.
        if (s != null && s.tabId == tabId && s.videoId == videoId && !manual) return
        // Another video on the same page started while ours is overlaid
        // (preview loop, ad pre-roll, next-episode teaser): an automatic report
        // used to replace the session silently, which looked like "our player
        // reverted" (v1.3.97). Only a manual pick replaces a live session.
        // The report is parked, not dropped: players that swap the <video>
        // element report the new one before the old one's gone/0-box lands,
        // and JS throttles the follow-up report — so the session's own end
        // promotes it (see [promoteDeferred]).
        if (s != null && s.tabId == tabId && !manual) {
            if (ignoredLogged.add(videoId)) {
                DebugLog.d("InlinePlayer", "교체 중 다른 영상 자동 보고 보류 ($videoId) — 현재 세션 유지")
            }
            val kept = deferred?.takeIf { it.tabId == tabId && it.videoId == videoId }
            deferred = InlinePlayRequest(
                tabId, videoId, domSrc, positionSec, rect, false,
                kept?.createdAt ?: SystemClock.uptimeMillis()
            )
            deferredAt = SystemClock.uptimeMillis()
            return
        }
        val prev = request
        // A pending manual pick is answered on the next composition; an
        // autoplay report landing in between must not replace it.
        if (!manual && prev?.manual == true) return
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
        // Any rect (even a 0-box) proves JS is tracking the taken video.
        main.removeCallbacks(noTrack)
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
        val parked = deferred
        val s = end() ?: return
        releaseHandler?.invoke(s)
        onAutoEnded?.invoke(s, "hidden")
        promoteDeferred(parked, s)
    }

    /** The taken video left the DOM: drop the overlay (JS already stopped tracking). */
    fun reportGone(tabId: String, videoId: String) {
        val s = session ?: return
        if (s.tabId != tabId || s.videoId != videoId) return
        val parked = deferred
        end()
        onAutoEnded?.invoke(s, "gone")
        promoteDeferred(parked, s)
    }

    private fun endNoTrack() {
        val parked = deferred
        val s = end() ?: return
        DebugLog.d("InlinePlayer", "교체 뒤 ${NO_TRACK_MS}ms 동안 영상 위치 보고 없음 (${s.videoId})")
        releaseHandler?.invoke(s)
        onAutoEnded?.invoke(s, "notrack")
        promoteDeferred(parked, s)
    }

    /**
     * The session ended because its video collapsed, left the DOM or was
     * never tracked; if the page reported another video just before
     * (element swap), resolve that one now. A park older than
     * [PROMOTE_WINDOW_MS] is a video that played long ago (a preview loop
     * that has since stopped) — promoting it would lay a ghost overlay over
     * whatever sits there now. BrowserScreen still applies the setting gate
     * and the 30 s deadline.
     */
    private fun promoteDeferred(parked: InlinePlayRequest?, ended: InlineSession) {
        if (parked == null || parked.tabId != ended.tabId || parked.videoId == ended.videoId) return
        if (SystemClock.uptimeMillis() - deferredAt > PROMOTE_WINDOW_MS) return
        if (request?.manual == true) return
        DebugLog.d("InlinePlayer", "보류했던 영상(${parked.videoId})으로 이어서 교체 시도")
        request = parked
    }

    /**
     * Our player failed on this stream: an automatic report for the same
     * stream would take it again and fail again (site autoplay retry → loop of
     * error toasts). Remembered until the tab loads a new document.
     */
    fun noteFailed(tabId: String, url: String) {
        if (failed.size >= MAX_FAILED) failed.clear()
        failed.add("$tabId|$url")
    }

    fun hasFailed(tabId: String, url: String): Boolean = "$tabId|$url" in failed

    /** A manual report from a tab that is not in front: counted, nothing else. */
    fun noteManualIgnored() { manualReports++ }

    /** The long-press menu is asking the page for a manual report now. */
    fun armManual() { manualArmedUntil = SystemClock.uptimeMillis() + MANUAL_ARM_MS }

    /** True (once) if a manual report is expected; consumes the window. */
    fun takeManualArm(): Boolean {
        val armed = SystemClock.uptimeMillis() < manualArmedUntil
        if (armed) manualArmedUntil = 0L
        return armed
    }

    /** The page answered with a failure: no manual report is coming. */
    fun disarmManual() { manualArmedUntil = 0L }

    /** Main-looper delay that does not depend on a (possibly detached) View. */
    fun postDelayed(delayMs: Long, action: () -> Unit) {
        main.postDelayed(Runnable { action() }, delayMs)
    }

    fun consumeRequest() { request = null }

    fun start(session: InlineSession, rect: InlineRect) {
        clearTimers()
        deferred = null
        ignoredLogged.clear()
        this.session = session
        this.rect = rect
        lastRectAppliedAt = SystemClock.uptimeMillis()
        request = null
        main.postDelayed(noTrack, NO_TRACK_MS)
    }

    /**
     * Swaps the live session for [next] on the same video — the overlay
     * rebuilds its player (it is keyed by session value) while the rect,
     * timers and the parked report stay as they are. Used for the one
     * header retry after a 401/403 (v1.3.98). Deliberately not [start]:
     * that re-arms [noTrack], and JS only re-emits a rect after `take()` or
     * when the box moves, so a still video would be ended as "notrack".
     */
    fun replaceSession(next: InlineSession): Boolean {
        val s = session ?: return false
        if (s.tabId != next.tabId || s.videoId != next.videoId) return false
        session = next
        return true
    }

    /** Clears the overlay and returns what was showing (for the JS release). */
    fun end(): InlineSession? {
        clearTimers()
        deferred = null
        val s = session
        session = null
        rect = null
        return s
    }

    /** Tab navigated away or closed: forget anything belonging to it. */
    fun dropTab(tabId: String) {
        if (request?.tabId == tabId) request = null
        failed.removeAll { it.startsWith("$tabId|") }
        val s = session ?: return
        if (s.tabId != tabId) return
        end()
        onAutoEnded?.invoke(s, "nav")
    }

    private fun clearTimers() {
        main.removeCallbacks(flushRect)
        main.removeCallbacks(unusableGrace)
        main.removeCallbacks(noTrack)
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
        const val MANUAL_ARM_MS = 3_000L
        const val MAX_FAILED = 32
        const val NO_TRACK_MS = 1_500L
        const val PROMOTE_WINDOW_MS = 2_000L
    }
}
