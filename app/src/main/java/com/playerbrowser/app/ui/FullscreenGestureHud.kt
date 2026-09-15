package com.playerbrowser.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View

/**
 * Native heads-up display for the gestures we layer on top of the WebView's
 * native fullscreen (see [GestureCapturingFrame]).
 *
 * Why native instead of the page's DOM: while an element is fullscreen the
 * browser renders *only* that element's subtree, and on top of that WebView
 * hands the fullscreen content to `onShowCustomView` as a separate native View
 * stacked over the WebView. A `position:fixed` div appended to
 * `document.documentElement` (what `window.__pb.showVbOverlay` / the JS toast
 * did) therefore can never be painted during native fullscreen — the gesture
 * worked but gave no feedback at all. Drawing here also sidesteps the
 * cross-origin iframe problem entirely: we don't need to reach the document
 * that owns the <video> just to say "volume 40%".
 *
 * The view never consumes touch: it stays non-clickable so the parent frame's
 * dispatch keeps flowing to the player below it.
 */
internal class FullscreenGestureHud(context: Context) : View(context) {

    enum class Level { Volume, Brightness }

    private sealed interface Content {
        data class Bar(val level: Level, val ratio: Float) : Content
        data class Message(val text: String) : Content
    }

    private val density = resources.displayMetrics.density

    private var content: Content? = null

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xCC, 0x10, 0x10, 0x10)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0x3D, 0xFF, 0xFF, 0xFF)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val labelPaint = textPaint(13f, Color.argb(0xDD, 0xFF, 0xFF, 0xFF))
    private val valuePaint = textPaint(15f)
    private val messagePaint = textPaint(17f)

    private val hideRunnable = Runnable { fadeOut() }
    private val rect = RectF()

    init {
        isClickable = false
        isFocusable = false
        visibility = GONE
        alpha = 0f
    }

    /** Volume / brightness level. Stays up until [hide] — the drag owns it. */
    fun showLevel(level: Level, ratio: Float) {
        removeCallbacks(hideRunnable)
        content = Content.Bar(level, ratio.coerceIn(0f, 1f))
        appear()
    }

    /** One-shot note (seek, video switch). Fades itself out. */
    fun showMessage(text: String) {
        removeCallbacks(hideRunnable)
        content = Content.Message(text)
        appear()
        postDelayed(hideRunnable, MESSAGE_MS)
    }

    fun hide() {
        removeCallbacks(hideRunnable)
        fadeOut()
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        when (val c = content) {
            is Content.Bar -> drawBar(canvas, c)
            is Content.Message -> drawMessage(canvas, c)
            null -> Unit
        }
    }

    private fun drawBar(canvas: Canvas, bar: Content.Bar) {
        val panelW = dp(78f)
        val panelH = dp(212f)
        val margin = dp(28f)
        val left = if (bar.level == Level.Brightness) {
            margin + cutoutInset(Side.Left)
        } else {
            width - margin - cutoutInset(Side.Right) - panelW
        }
        val top = (height - panelH) / 2f
        rect.set(left, top, left + panelW, top + panelH)
        canvas.drawRoundRect(rect, dp(16f), dp(16f), panelPaint)

        val cx = left + panelW / 2f
        val label = if (bar.level == Level.Volume) "음량" else "밝기"
        canvas.drawText(label, cx, top + dp(26f), labelPaint)

        val trackTop = top + dp(42f)
        val trackH = dp(128f)
        val trackW = dp(10f)
        rect.set(cx - trackW / 2f, trackTop, cx + trackW / 2f, trackTop + trackH)
        canvas.drawRoundRect(rect, trackW / 2f, trackW / 2f, trackPaint)

        val fillH = trackH * bar.ratio
        if (fillH > 0f) {
            rect.set(cx - trackW / 2f, trackTop + trackH - fillH, cx + trackW / 2f, trackTop + trackH)
            canvas.drawRoundRect(rect, trackW / 2f, trackW / 2f, fillPaint)
        }

        val percent = "${Math.round(bar.ratio * 100)}%"
        canvas.drawText(percent, cx, top + panelH - dp(18f), valuePaint)
    }

    private fun drawMessage(canvas: Canvas, message: Content.Message) {
        val padH = dp(22f)
        val padV = dp(13f)
        val textW = messagePaint.measureText(message.text)
        val fm = messagePaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val cx = width / 2f
        val halfW = textW / 2f + padH
        val halfH = textH / 2f + padV
        val cy = maxOf(height * MESSAGE_Y_RATIO, cutoutInset(Side.Top) + halfH + dp(8f))
        rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        canvas.drawRoundRect(rect, halfH, halfH, panelPaint)
        canvas.drawText(message.text, cx, cy - (fm.ascent + fm.descent) / 2f, messagePaint)
    }

    private fun appear() {
        animate().cancel()
        alpha = 1f
        if (visibility != VISIBLE) visibility = VISIBLE
        invalidate()
    }

    private fun fadeOut() {
        if (visibility != VISIBLE) return
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withEndAction {
                visibility = GONE
                content = null
            }
            .start()
    }

    private fun textPaint(sizeSp: Float, color: Int = Color.WHITE) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            textSize = sizeSp * resources.displayMetrics.scaledDensity
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    private enum class Side { Left, Top, Right }

    // Fullscreen lets the window extend into the camera cutout (v1.3.93), so
    // keep the panels out of it or the hole bites into the bar.
    private fun cutoutInset(side: Side): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0f
        val cutout = rootWindowInsets?.displayCutout ?: return 0f
        return when (side) {
            Side.Left -> cutout.safeInsetLeft
            Side.Top -> cutout.safeInsetTop
            Side.Right -> cutout.safeInsetRight
        }.toFloat()
    }

    private fun dp(value: Float) = value * density

    private companion object {
        const val MESSAGE_MS = 700L
        const val FADE_MS = 180L
        const val MESSAGE_Y_RATIO = 0.24f
    }
}
