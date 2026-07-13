package com.playerbrowser.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Section key used for the "no group" bucket — group ids are UUIDs so this can't collide. */
internal const val UNGROUPED_SECTION_KEY = "__ungrouped__"

/** A registered drop region: a group header (tabId == null) or a tab card. */
private data class DropRegion(val sectionKey: String, val tabId: String?, val bounds: Rect)

/** Where a tab drop would land, resolved from the finger position over the grid. */
internal data class DropResolution(
    val sectionKey: String,
    /** The card under the finger, or null when over a header / empty section. */
    val anchorTabId: String?,
    /** Insert after the anchor (finger past its horizontal center) vs. before. */
    val placeAfter: Boolean
)

/** Where a group drop would land: before/after [anchorGroupId], or at the end when null. */
internal data class GroupDropResolution(
    val anchorGroupId: String?,
    val placeAfter: Boolean
)

/**
 * State for one drag session in the tab switcher — either tab cards being moved
 * between/within groups, or a whole group being reordered by its header.
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
 *
 * Ownership split (crucial for edge auto-scroll): the lazy-item drag sources
 * ([tabDragSource] / [groupDragSource]) only *start* a session on long press.
 * All movement and the release are then owned by [tabDragContainer] on the
 * grid's host Box, which is never disposed. If the source card/header scrolls
 * out of the viewport during auto-scroll, the LazyGrid disposes it and its
 * pointer coroutine dies — with item-owned drags that killed the session
 * mid-air (the "drag cuts out while scrolling up" bug); with container-owned
 * drags the session survives until the finger actually lifts.
 */
internal class TabDragState {
    /** Tab ids currently being dragged, null when no tab-drag session is active. */
    var draggingIds by mutableStateOf<List<String>?>(null)

    /** Group id being dragged for section reorder, null when not a group drag. */
    var draggingGroupId by mutableStateOf<String?>(null)

    /** True while either a tab drag or a group drag session is active. */
    val isActive: Boolean get() = draggingIds != null || draggingGroupId != null

    /** Finger position in root coordinates while dragging (drives the preview). */
    var pointer by mutableStateOf(Offset.Zero)

    /** Section key (group id or [UNGROUPED_SECTION_KEY]) currently hovered, for highlight. */
    var hoverSectionKey by mutableStateOf<String?>(null)

    /** Card currently hovered, for the insertion indicator. Null over a header / gap. */
    var hoverTabId by mutableStateOf<String?>(null)

    /** True when the insertion bar sits on the trailing (right) edge of [hoverTabId]. */
    var hoverAfter by mutableStateOf(false)

    /** Group drag only: insert after (below) the hovered section vs. before it. */
    var hoverAfterSection by mutableStateOf(false)

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

    fun beginGroup(groupId: String, position: Offset) {
        draggingGroupId = groupId
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
        if (draggingGroupId != null) {
            // Group drags target whole sections — no per-card insertion bar.
            hoverTabId = null
            hoverAfterSection = region?.let { r ->
                if (r.tabId == null) position.y > r.bounds.center.y else true
            } ?: false
            return
        }
        // Don't draw an insertion bar on a card that is itself being dragged.
        val overDragged = region?.tabId != null && draggingIds?.contains(region.tabId) == true
        hoverTabId = if (overDragged) null else region?.tabId
        hoverAfter = region?.let { position.x > it.bounds.center.x } ?: false
    }

    /** Resolves the current pointer to a tab drop, or null when outside every region. */
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

    /**
     * Resolves the current pointer to a group-reorder drop. Hitting a group's
     * header decides before/after by the vertical center; hitting one of its
     * cards means "right after that group". The ungrouped section (always last)
     * resolves to "end of the group list". Self-drops resolve to null (no-op).
     */
    fun resolveGroupDrop(): GroupDropResolution? {
        val dragging = draggingGroupId ?: return null
        val region = regionAt(pointer) ?: return null
        val key = region.sectionKey
        if (key == dragging) return null
        if (key == UNGROUPED_SECTION_KEY) return GroupDropResolution(anchorGroupId = null, placeAfter = true)
        val after = if (region.tabId == null) pointer.y > region.bounds.center.y else true
        return GroupDropResolution(anchorGroupId = key, placeAfter = after)
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
        draggingGroupId = null
        hoverSectionKey = null
        hoverTabId = null
        hoverAfter = false
        hoverAfterSection = false
        autoScrollDirection = 0
    }
}

/**
 * Owns the move/release half of every drag session. Must sit on the grid's host
 * Box (which never leaves composition), NOT on a lazy item: once a session is
 * active this handler consumes all pointer events in the Initial pass — feeding
 * positions into [dragState], keeping the grid's own scrollable from grabbing
 * the gesture, and resolving the drop on release — so the drag keeps working
 * even after edge auto-scroll pushes the source card/header out of the viewport
 * and the LazyGrid disposes it (which kills the item-side pointer coroutine).
 *
 * Positions are container-local; they're mapped to root space via
 * [TabDragState.gridBounds], which the same Box already reports.
 */
internal fun Modifier.tabDragContainer(
    dragState: TabDragState,
    onDrop: () -> Unit
): Modifier = pointerInput(dragState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (dragState.isActive) {
                    if (event.changes.all { !it.pressed }) {
                        // Consume the up too so the source card's clickable
                        // doesn't also fire a click at the drop point.
                        event.changes.forEach { it.consume() }
                        onDrop()
                        break
                    }
                    event.changes.forEach { it.consume() }
                    val base = dragState.gridBounds?.topLeft ?: Offset.Zero
                    dragState.moveTo(base + event.changes.first().position)
                } else if (event.changes.all { !it.pressed }) {
                    break
                }
            }
        } finally {
            // Covers the drop path and abnormal gesture teardown (system cancel)
            // alike — the session must never outlive the finger.
            if (dragState.isActive) dragState.reset()
        }
    }
}

/**
 * Makes a tab card start a drag session with a self-contained pointer drag. The
 * long press that precedes it still belongs to the card's combinedClickable
 * (which enters selection mode); this detector then calls [TabDragState.begin],
 * after which [tabDragContainer] takes over movement and release (its Initial-
 * pass consumption cancels this detector — intentionally, so the session doesn't
 * die with the card if auto-scroll pushes it out of composition).
 *
 * Must sit AFTER combinedClickable in the modifier chain so this detector wins
 * the Main pointer pass and the clickable's post-long-press consumeUntilUp can't
 * swallow the long-press start. Finger positions are converted to root
 * coordinates using the card's own root offset so they line up with the region
 * rects, which are also stored in root space.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.tabDragSource(
    dragState: TabDragState,
    haptics: HapticFeedback,
    draggedIds: () -> List<String>
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
                // The container owns release/cancel; doing anything here would
                // kill the session the moment this lazy item is disposed.
                onDragEnd = {},
                onDragCancel = {}
            )
        }
}

/** Same as [tabDragSource] but for a group header — starts a group-reorder drag. */
internal fun Modifier.groupDragSource(
    dragState: TabDragState,
    haptics: HapticFeedback,
    groupId: String
): Modifier = composed {
    var topLeft by remember { mutableStateOf(Offset.Zero) }
    Modifier
        .onGloballyPositioned { topLeft = it.boundsInRoot().topLeft }
        .pointerInput(groupId) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    dragState.beginGroup(groupId, topLeft + offset)
                },
                onDrag = { change, _ ->
                    change.consume()
                    dragState.moveTo(topLeft + change.position)
                },
                onDragEnd = {},
                onDragCancel = {}
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
