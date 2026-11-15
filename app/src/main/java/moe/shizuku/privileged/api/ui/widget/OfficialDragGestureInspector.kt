package moe.shizuku.privileged.api.ui.widget

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.util.fastFirstOrNull
import kotlin.math.abs

/**
 * 官方 DragGestureInspector 完整移植版
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (AwaitPointerEventScope.(PointerInputChange) -> Unit)? = null,
    onDragEnd: (AwaitPointerEventScope.() -> Unit)? = null,
    onDragCancel: (AwaitPointerEventScope.() -> Unit)? = null,
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    var pointerId: PointerId? = null
    var startPosition: Offset? = null
    var lastPosition: Offset? = null
    var inDrag: Boolean

    awaitPointerEventScope {
        inDrag = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.fastFirstOrNull { it.id == pointerId }

            if (change == null) {
                if (inDrag) {
                    inDrag = false
                    onDragCancel?.invoke(this)
                }
                pointerId = null
                startPosition = null
                lastPosition = null
                continue
            }

            if (!inDrag) {
                val totalDx = change.position.x - (startPosition?.x ?: change.position.x)
                val totalDy = change.position.y - (startPosition?.y ?: change.position.y)
                val totalDistance = abs(totalDx) + abs(totalDy)

                if (totalDistance > touchSlop) {
                    inDrag = true
                    onDragStart?.invoke(this, change)
                }
            }

            if (inDrag) {
                val delta = change.position - (lastPosition ?: change.position)
                onDrag(change, delta)
                lastPosition = change.position
            }

            if (!change.pressed) {
                if (inDrag) {
                    inDrag = false
                    onDragEnd?.invoke(this)
                }
                pointerId = null
                startPosition = null
                lastPosition = null
            }
        }
    }
}
