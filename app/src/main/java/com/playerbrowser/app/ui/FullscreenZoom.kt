package com.playerbrowser.app.ui

import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pinch zoom for the native fullscreen view (v1.3.96), driven by
 * [GestureCapturingFrame].
 *
 * The zoom is a plain View transform (scale + translation) on the CustomView
 * WebView hands to `onShowCustomView` — not CSS on the page. That keeps it out
 * of the page entirely (no JS, works the same for cross-origin iframe players
 * and never touches a challenge page), and taps still land where they look:
 * ViewGroup maps every forwarded touch through the child's inverse matrix, so
 * the site's controls keep working on the zoomed picture.
 *
 * The default stays the uncropped fit (v1.3.94) — zoom is something the user
 * does and can always undo; it never goes below 1x, and the translation is
 * clamped so the picture always covers the frame (no gap opens at an edge).
 */
internal class FullscreenZoom(
    private val slopPx: Float,
    private val target: () -> View?
) {
    var scale = 1f
        private set
    private var tx = 0f
    private var ty = 0f

    val isZoomed: Boolean get() = scale > 1f

    // Per gesture: from fit, a pinch engages only once the fingers spread apart
    // by [slopPx] *and* the spread outpaces the fingers' travel, so two fingers
    // sliding sideways (the video-switch swipe) never turn into a zoom even if
    // they drift apart on the way. Baselines are re-taken whenever a finger is
    // added or lifted, otherwise the focal point jumps.
    var engaged = false
        private set
    private var startSpan = 0f
    private var startFx = 0f
    private var startFy = 0f
    private var lastSpan = 0f
    private var lastFx = 0f
    private var lastFy = 0f
    private var lastCount = 0

    fun beginGesture() {
        engaged = false
        lastCount = 0
    }

    /**
     * A finger was added or lifted: the next event re-takes the baselines even
     * when the count ends up the same (lift one finger, put it down elsewhere
     * with no MOVE in between), so the picture doesn't jump.
     */
    fun pointersChanged() {
        lastCount = -1
    }

    /**
     * Feed a multi-touch MOVE. [immediate] skips the spread threshold (the
     * picture was already zoomed when the gesture began, so two fingers mean
     * zoom/pan rather than a switch). Returns whether the zoom owns the gesture.
     */
    fun onMultiTouchMove(ev: MotionEvent, immediate: Boolean): Boolean {
        val count = ev.pointerCount
        var sumX = 0f
        var sumY = 0f
        for (i in 0 until count) {
            sumX += ev.getX(i)
            sumY += ev.getY(i)
        }
        val fx = sumX / count
        val fy = sumY / count
        var dev = 0f
        for (i in 0 until count) dev += hypot(ev.getX(i) - fx, ev.getY(i) - fy)
        val span = dev / count * 2f

        if (count != lastCount) {
            lastCount = count
            rebase(span, fx, fy)
            if (!engaged) {
                startSpan = span
                startFx = fx
                startFy = fy
            }
            return engaged
        }
        if (!engaged) {
            if (!immediate && !isPinchOut(count, span, fx, fy)) return false
            engaged = true
            // Start from here rather than from the touch-down spread, so the
            // picture doesn't leap by the threshold distance.
            rebase(span, fx, fy)
            return true
        }
        if (count >= 2 && lastSpan > 0f) scaleAround(span / lastSpan, fx, fy)
        panBy(fx - lastFx, fy - lastFy)
        rebase(span, fx, fy)
        return true
    }

    fun panBy(dx: Float, dy: Float) {
        tx += dx
        ty += dy
        apply()
    }

    /**
     * End of a zoom gesture: a picture left barely zoomed snaps back to fit,
     * otherwise a 101% zoom would quietly turn every one-finger drag into a pan.
     * Returns true when the picture ends at its original size.
     */
    fun settle(): Boolean {
        if (scale < SNAP_TO_FIT) reset()
        return !isZoomed
    }

    fun reset() {
        scale = 1f
        tx = 0f
        ty = 0f
        apply()
    }

    /** Re-clamp after the frame is laid out at a new size (rotation). */
    fun reclamp() {
        if (isZoomed) apply()
    }

    // From fit only a spread that grows can zoom (pinching in has nowhere to
    // go below 1x), and it has to dominate the fingers' travel — a sideways
    // switch swipe that drifts 30dp apart over 150dp is still a swipe.
    private fun isPinchOut(count: Int, span: Float, fx: Float, fy: Float): Boolean {
        if (count < 2) return false
        val grow = span - startSpan
        return grow >= slopPx && grow >= SPREAD_OVER_TRAVEL * hypot(fx - startFx, fy - startFy)
    }

    private fun rebase(span: Float, fx: Float, fy: Float) {
        lastSpan = span
        lastFx = fx
        lastFy = fy
    }

    // Keep the content point under the focal point fixed. With the default
    // center pivot c, a point p shows at c + (p - c)·s + t, so going from s0 to
    // s1 = k·s0 needs t1 = (f - c)(1 - k) + t0·k.
    private fun scaleAround(factor: Float, fx: Float, fy: Float) {
        val v = target() ?: return
        val newScale = (scale * factor).coerceIn(1f, MAX_ZOOM)
        val k = newScale / scale
        val cx = v.left + v.width / 2f
        val cy = v.top + v.height / 2f
        tx = (fx - cx) * (1f - k) + tx * k
        ty = (fy - cy) * (1f - k) + ty * k
        scale = newScale
        apply()
    }

    // ±(s - 1)·size/2 is exactly how far the scaled picture overhangs each
    // side, so staying inside it never opens a gap at an edge.
    private fun apply() {
        val v = target() ?: return
        val maxTx = (scale - 1f) * v.width / 2f
        val maxTy = (scale - 1f) * v.height / 2f
        tx = tx.coerceIn(-maxTx, maxTx)
        ty = ty.coerceIn(-maxTy, maxTy)
        v.scaleX = scale
        v.scaleY = scale
        v.translationX = tx
        v.translationY = ty
    }

    private companion object {
        const val MAX_ZOOM = 4f
        // Below this a zoom is hard to see but would still turn every
        // one-finger drag into a pan — snap it back to fit.
        const val SNAP_TO_FIT = 1.1f
        const val SPREAD_OVER_TRAVEL = 0.5f
    }
}
