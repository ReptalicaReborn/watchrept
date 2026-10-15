package com.dihcord.app.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration

/** Shared state of a drag-to-reorder gesture over the server grid. All positions are in window coordinates. */
@Stable
class ReorderState {
    /** Key of the entry being dragged, or null when nothing is. */
    var dragKey by mutableStateOf<String?>(null)
        internal set

    /** Where the finger is. */
    var dragPos by mutableStateOf(Offset.Zero)
        internal set

    /** Key of the entry under the finger (the drop target), if any. */
    var targetKey by mutableStateOf<String?>(null)
        internal set

    internal var moved = false
    internal val bounds = HashMap<String, Rect>()
}

/**
 * Makes a grid tile tappable and draggable. Long-press lifts it; dragging over another tile marks it as the target
 * and releasing there calls [onDrop]. Releasing a long-press without moving calls [onMenu] instead.
 */
fun Modifier.reorderable(
    key: String,
    state: ReorderState,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onDrop: (fromKey: String, toKey: String) -> Unit,
): Modifier = composed {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val haptic = LocalHapticFeedback.current
    val slop = LocalViewConfiguration.current.touchSlop
    val currentClick by rememberUpdatedState(onClick)
    val currentMenu by rememberUpdatedState(onMenu)
    val currentDrop by rememberUpdatedState(onDrop)

    DisposableEffect(key) { onDispose { state.bounds.remove(key) } }

    this
        .onGloballyPositioned {
            coords = it
            state.bounds[key] = it.boundsInWindow()
        }
        .pointerInput(key) { detectTapGestures(onTap = { currentClick() }) }
        .pointerInput(key) {
            var start = Offset.Zero
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    start = offset
                    state.moved = false
                    state.targetKey = null
                    state.dragPos = coords?.localToWindow(offset) ?: Offset.Zero
                    state.dragKey = key
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDrag = { change, _ ->
                    change.consume()
                    val pos = coords?.localToWindow(change.position) ?: return@detectDragGesturesAfterLongPress
                    state.dragPos = pos
                    if ((change.position - start).getDistance() > slop * 2) state.moved = true
                    state.targetKey = state.bounds.entries
                        .firstOrNull { it.key != key && it.value.contains(pos) }?.key
                },
                onDragEnd = {
                    val target = state.targetKey
                    val moved = state.moved
                    state.dragKey = null
                    state.targetKey = null
                    if (target != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentDrop(key, target)
                    } else if (!moved) {
                        currentMenu()
                    }
                },
                onDragCancel = {
                    state.dragKey = null
                    state.targetKey = null
                },
            )
        }
}
