package com.playerbrowser.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Section key used for the "no group" bucket — group ids are UUIDs so this can't collide. */
internal const val UNGROUPED_SECTION_KEY = "__ungrouped__"

/** A registered drop region: a group header (tabId == null) or a tab card. */
private data class DropRegion(val sectionKey: String, val tabId: String?, val bounds: Rect)

/** Where a drop would land, resolved from the finger position over the grid. */
internal data class DropResolution(
    val sectionKey: String,
    /** The card under the finger, or null when over a header / empty section. */
    val anchorTabId: String?,
    /** Insert after the anchor (finger past its horizontal center) vs. before. */
    val placeAfter: Boolean
)

/**
 * State for one drag-to-reorder / drag-to-group session in the tab switcher.
 *
 * This is a fully self-contained, pointer-driven drag — NOT Android's platform
 * drag-and-drop. The earlier `dragAndDropSource`/`dragAndDropTarget` version
 * dropped events on-device (Compose 1.6.x delivers `onDrop` unreliably and
 * `startTransfer` can silently no-op), so the card would pick up but never land
 * in a group. Here the drag source feeds finger positions straight into this
 * state, every header/card reports its on-screen bounds via [tabDropRegion], and
 * the drop is resolved by hit-testing those live rects at release time — no
 * platform round-trip, so it lands every time and also supports inserting a tab
 * *between* two cards (reorder), which section-only platform drops could not.
 */
internal class TabDragState {
    /** Tab ids currently being dragged, null when no session is active. */
    var draggingIds by mutableStateOf<List<String>?>(null)

    /** Finger position in root coordinates while dragging (drives the preview). */
    var pointer by mutableStateOf(Offset.Zero)

    /** Section key (group id or [UNGROUPED_SECTION_KEY]) currently hovered, for highlight. */
    var hoverSectionKey by mutableStateOf<String?>(null)

    /** Card currently hovered, for the insertion indicator. Null over a header / gap. */
    var hoverTabId by mutableStateOf<String?>(null)

    /** True when the insertion bar sits on the trailing (right) edge of [hoverTabId]. */
    var hoverAfter by mutableStateOf(false)

    /** -1 scroll up, +1 scroll down, 0 idle — driven by edge proximity while dragging. */
    var autoScrollDirection by mutableStateOf(0)

    /** Grid container bounds in root coordinates; set from onGloballyPositioned. */
    var gridBounds: Rect? = null

    /** Edge band height (px) that triggers auto-scroll. */
    var edgeZonePx: Float = 0f

    private val regions = mutableMapOf<String, DropRegion>()

    fun updateRegion(regionId: String, sectionKey: String, tabId: String?, bounds: Rect) {
        regions[regionId] = DropRegion(sectionKey, tabId, bounds)
    }

    fun removeRegion(regionId: String) {
        regions.remove(regionId)
    }

    /** Prefer a tab-card hit (finer control) and fall back to a header/section hit. */
    private fun regionAt(position: Offset): DropRegion? {
        val hits = regions.values.filter { it.bounds.contains(position) }
        return hits.firstOrNull { it.tabId != null } ?: hits.firstOrNull()
    }

    fun begin(ids: List<String>, position: Offset) {
        draggingIds = ids
        pointer = position
        updateHover(position)
    }

    fun moveTo(position: Offset) {
        pointer = position
        updateHover(position)
        autoScrollDirection = scrollDirectionFor(position.y)
    }

    /**
     * Re-resolves the hover against the *current* region rects using the last
     * finger position. The auto-scroll loop calls this after every scroll step so
     * the highlight / insertion bar (and thus the eventual drop) track the content
     * moving under a stationary finger — otherwise they'd freeze at wherever the
     * finger last actually moved.
     */
    fun refreshHover() {
        updateHover(pointer)
    }

    private fun updateHover(position: Offset) {
        val region = regionAt(position)
        hoverSectionKey = region?.sectionKey
        // Don't draw an insertion bar on a card that is itself being dragged.
        val overDragged = region?.tabId != null && draggingIds?.contains(region.tabId) == true
        hoverTabId = if (overDragged) null else region?.tabId
        hoverAfter = region?.let { position.x > it.bounds.center.x } ?: false
    }

    /** Resolves the current pointer to a drop, or null when outside every region. */
    fun resolve(): DropResolution? {
        val ids = draggingIds ?: return null
        val region = regionAt(pointer) ?: return null
        val anchor = region.tabId?.takeUnless { it in ids } // never anchor to a dragged card
        // Recompute placeAfter against the region we just hit rather than reusing
        // the cached hoverAfter, so the landing side always matches this region
        // even if the content scrolled since the last updateHover.
        val placeAfter = anchor != null && pointer.x > region.bounds.center.x
        return DropResolution(region.sectionKey, anchor, placeAfter)
    }

    private fun scrollDirectionFor(y: Float): Int {
        val bounds = gridBounds ?: return 0
        return when {
            y < bounds.top + edgeZonePx -> -1
            y > bounds.bottom - edgeZonePx -> 1
            else -> 0
        }
    }

    fun reset() {
        draggingIds = null
        hoverSectionKey = null
        hoverTabId = null
        hoverAfter = false
        autoScrollDirection = 0
    }
}

/**
 * Makes a tab card draggable with a self-contained pointer drag. The long press
 * that precedes it still belongs to the card's combinedClickable (which enters
 * selection mode); once the finger travels past touch slop this detector begins
 * feeding finger positions into [dragState] and, on release, calls [onDrop].
 *
 * Must sit AFTER combinedClickable in the modifier chain so this detector wins
 * the Main pointer pass and the clickable's post-long-press consumeUntilUp can't
 * cancel the drag. Finger positions are converted to root coordinates using the
 * card's own root offset (tracked via onGloballyPositioned) so they line up with
 * the region rects, which are also stored in root space.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.tabDragSource(
    dragState: TabDragState,
    haptics: HapticFeedback,
    draggedIds: () -> List<String>,
    onDrop: () -> Unit
): Modifier = composed {
    var cardTopLeft by remember { mutableStateOf(Offset.Zero) }
    Modifier
        .onGloballyPositioned { cardTopLeft = it.boundsInRoot().topLeft }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    dragState.begin(draggedIds(), cardTopLeft + offset)
                },
                onDrag = { change, _ ->
                    change.consume()
                    dragState.moveTo(cardTopLeft + change.position)
                },
                onDragEnd = {
                    onDrop()
                    dragState.reset()
                },
                onDragCancel = { dragState.reset() }
            )
        }
}

/**
 * Registers this element's bounds as a drop region resolving to [sectionKey]
 * (and, for a card, [tabId] so drops can insert relative to it). Bounds refresh
 * on every reposition (including lazy-grid scrolling) and are dropped from the
 * hit-test map when the item leaves composition.
 */
@Composable
internal fun Modifier.tabDropRegion(
    dragState: TabDragState,
    regionId: String,
    sectionKey: String,
    tabId: String? = null
): Modifier {
    DisposableEffect(dragState, regionId) {
        onDispose { dragState.removeRegion(regionId) }
    }
    return onGloballyPositioned { coordinates ->
        dragState.updateRegion(regionId, sectionKey, tabId, coordinates.boundsInRoot())
    }
}
