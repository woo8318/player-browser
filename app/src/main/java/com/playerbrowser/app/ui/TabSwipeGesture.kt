package com.playerbrowser.app.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * Detects a horizontal flick across the toolbar / nav bar and reports it as a
 * left/right tab switch (Chrome / Opera style). Uses [detectHorizontalDragGestures]
 * so the detector only engages once the gesture is clearly horizontal — vertical
 * scrolls and taps fall through untouched, which keeps it from fighting the
 * URL text field's tap-to-focus or the bottom bar's button clicks.
 *
 * The switch fires once per drag, the moment the accumulated distance crosses
 * the threshold, so the tab swaps mid-gesture for immediate feedback.
 *
 * @param onPrevious invoked on a rightward swipe (toward the previous tab)
 * @param onNext invoked on a leftward swipe (toward the next tab)
 */
fun Modifier.tabSwitchSwipe(
    onPrevious: () -> Unit,
    onNext: () -> Unit
): Modifier = this.pointerInput(onPrevious, onNext) {
    // ~3x touch slop: enough to be a deliberate flick, not a stray drag.
    val threshold = viewConfiguration.touchSlop * 3f
    var accumulated = 0f
    var fired = false
    detectHorizontalDragGestures(
        onDragStart = {
            accumulated = 0f
            fired = false
        },
        onHorizontalDrag = { change, dragAmount ->
            accumulated += dragAmount
            if (!fired && abs(accumulated) >= threshold) {
                fired = true
                if (accumulated < 0f) onNext() else onPrevious()
                change.consume()
            }
        }
    )
}
