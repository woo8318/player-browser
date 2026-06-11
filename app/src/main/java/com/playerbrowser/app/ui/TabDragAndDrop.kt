package com.playerbrowser.app.ui

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Section key used for the "no group" bucket — group ids are UUIDs so this can't collide. */
internal const val UNGROUPED_SECTION_KEY = "__ungrouped__"

/** ClipDescription label that marks a drag session as ours. */
internal const val TAB_DRAG_CLIP_LABEL = "player-browser/tab-ids"

/**
 * State for one drag-to-group session in the tab switcher.
 *
 * Drop targets are NOT per-item [dragAndDropTarget] nodes on purpose: in Compose 1.6.x
 * a target node attached after the drag session started (e.g. a lazy-grid item scrolled
 * into view mid-drag) never joins the session. Instead every header/card reports its
 * on-screen bounds here via [tabDropRegion], and a single root target hit-tests against
 * those live rects — items composed mid-drag stay droppable.
 */
internal class TabDragState {
    /** Tab ids currently being dragged, null when no session is active. */
    var draggingIds by mutableStateOf<List<String>?>(null)

    /** Section key (group id or [UNGROUPED_SECTION_KEY]) currently hovered, for highlight. */
    var hoverSectionKey by mutableStateOf<String?>(null)

    /** -1 scroll up, +1 scroll down, 0 idle — driven by edge proximity while dragging. */
    var autoScrollDirection by mutableStateOf(0)

    /** Grid container bounds in root coordinates; set from onGloballyPositioned. */
    var gridBounds: Rect? = null

    /** Edge band height (px) that triggers auto-scroll. */
    var edgeZonePx: Float = 0f

    /**
     * True once the platform delivered ACTION_DRAG_STARTED for our session.
     * startTransfer can silently fail (View.startDragAndDrop returns false and
     * 1.6.8 ignores it) — the overlay uses this to detect and undo a session
     * that never actually began.
     */
    var sessionStarted: Boolean = false

    private val regions = mutableMapOf<String, Pair<String, Rect>>()

    fun updateRegion(regionId: String, sectionKey: String, bounds: Rect) {
        regions[regionId] = sectionKey to bounds
    }

    fun removeRegion(regionId: String) {
        regions.remove(regionId)
    }

    fun sectionAt(position: Offset): String? =
        regions.values.firstOrNull { (_, rect) -> rect.contains(position) }?.first

    fun onDragMoved(position: Offset) {
        hoverSectionKey = sectionAt(position)
        autoScrollDirection = scrollDirectionFor(position.y)
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
        autoScrollDirection = 0
        sessionStarted = false
    }
}

internal fun isTabDragEvent(event: DragAndDropEvent): Boolean =
    event.toAndroidDragEvent().clipDescription?.label?.toString() == TAB_DRAG_CLIP_LABEL

private fun draggedTabIds(event: DragAndDropEvent): List<String> {
    val dragEvent = event.toAndroidDragEvent()
    val local = (dragEvent.localState as? List<*>)?.filterIsInstance<String>()
    if (!local.isNullOrEmpty()) return local
    // Fallback for the (in-process unlikely) case localState didn't survive.
    val clip = dragEvent.clipData ?: return emptyList()
    if (clip.itemCount == 0) return emptyList()
    return clip.getItemAt(0).text?.toString()
        ?.split(",")
        ?.filter { it.isNotBlank() }
        ?: emptyList()
}

/**
 * Makes a tab card draggable. The long press itself still belongs to the card's
 * combinedClickable (selection mode); only once the finger travels past touch slop
 * does the platform drag session start. Must sit AFTER combinedClickable in the
 * modifier chain so this detector wins the Main pointer pass and the clickable's
 * post-long-press consumeUntilUp can't cancel the drag.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.tabDragSource(
    haptics: HapticFeedback,
    draggedIds: () -> List<String>,
    onTransferStarted: (List<String>) -> Unit
): Modifier = dragAndDropSource {
    var transferStarted = false
    var travelled = Offset.Zero
    detectDragGesturesAfterLongPress(
        onDragStart = {
            transferStarted = false
            travelled = Offset.Zero
        },
        onDrag = { change, amount ->
            change.consume()
            if (!transferStarted) {
                travelled += amount
                if (travelled.getDistance() > viewConfiguration.touchSlop) {
                    transferStarted = true
                    val ids = draggedIds()
                    onTransferStarted(ids)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    startTransfer(
                        DragAndDropTransferData(
                            clipData = ClipData.newPlainText(
                                TAB_DRAG_CLIP_LABEL,
                                ids.joinToString(",")
                            ),
                            localState = ids
                        )
                    )
                }
            }
        },
        onDragEnd = {},
        onDragCancel = {}
    )
}

/**
 * Registers this element's bounds as a drop region resolving to [sectionKey].
 * Bounds refresh on every reposition (including lazy-grid scrolling) and are
 * dropped from the hit-test map when the item leaves composition.
 */
@Composable
internal fun Modifier.tabDropRegion(
    dragState: TabDragState,
    regionId: String,
    sectionKey: String
): Modifier {
    DisposableEffect(dragState, regionId) {
        onDispose { dragState.removeRegion(regionId) }
    }
    return onGloballyPositioned { coordinates ->
        dragState.updateRegion(regionId, sectionKey, coordinates.boundsInRoot())
    }
}

/**
 * The single drop target covering the whole grid. Tracks hover highlight and
 * auto-scroll on move, resolves the drop to a section via hit-test, and resets
 * the session state when the platform drag ends (dropped or not).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.tabDropTargetRoot(
    dragState: TabDragState,
    onDropToSection: (List<String>, String) -> Unit
): Modifier {
    val currentOnDrop by rememberUpdatedState(onDropToSection)
    val target = remember(dragState) {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                dragState.sessionStarted = true
            }

            override fun onMoved(event: DragAndDropEvent) {
                val dragEvent = event.toAndroidDragEvent()
                dragState.onDragMoved(Offset(dragEvent.x, dragEvent.y))
            }

            override fun onExited(event: DragAndDropEvent) {
                dragState.hoverSectionKey = null
                dragState.autoScrollDirection = 0
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val dragEvent = event.toAndroidDragEvent()
                val section = dragState.sectionAt(Offset(dragEvent.x, dragEvent.y))
                    ?: return false
                val ids = draggedTabIds(event)
                if (ids.isEmpty()) return false
                currentOnDrop(ids, section)
                return true
            }

            override fun onEnded(event: DragAndDropEvent) {
                dragState.reset()
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = ::isTabDragEvent,
        target = target
    )
}
