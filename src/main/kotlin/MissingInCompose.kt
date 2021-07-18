import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*

// Same as official `detectDragGestures` but give the initial down position before slop.
suspend fun PointerInputScope.detectDragGestures(
	onDragStart: (Offset, Offset) -> Unit = { _, _ -> },
	onDragEnd: () -> Unit = { },
	onDragCancel: () -> Unit = { },
	onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
	forEachGesture {
		awaitPointerEventScope {
			val down = awaitFirstDown(requireUnconsumed = false)
			var drag: PointerInputChange?
			var overSlop = Offset.Zero
			do {
				drag = awaitTouchSlopOrCancellation(down.id) { change, over ->
					change.consumePositionChange()
					overSlop = over
				}
			} while (drag != null && !drag.positionChangeConsumed())
			if (drag != null) {
				onDragStart.invoke(drag.position, down.position)
				onDrag(drag, overSlop)
				if (
					!drag(drag.id) {
						onDrag(it, it.positionChange())
						it.consumePositionChange()
					}
				) {
					onDragCancel()
				} else {
					onDragEnd()
				}
			}
		}
	}
}
