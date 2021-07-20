import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

internal data class SelectedItem(val srcIndex: Int, val key: Any)

class DragAndDropState(
	private val move: (Int, Int) -> Unit,
	internal val listState: LazyListState
) {
	internal var selectedItem by mutableStateOf<SelectedItem?>(null)
	internal var dragDelta: Float by mutableStateOf(0.0f)
	internal var shift: Int by mutableStateOf(0)

	internal val selectedItemIndex: Int by derivedStateOf {
		val selected = selectedItem?.key
		listState.layoutInfo.visibleItemsInfo.indexOfFirst { it.key == selected }
	}
	internal val selectedItemInfo: LazyListItemInfo? by derivedStateOf {
		val index = selectedItemIndex
		if (index != -1) {
			listState.layoutInfo.visibleItemsInfo[index]
		} else {
			null
		}
	}

	internal fun finishDrag() {
		val targetItem = selectedItem!!
		val shift = shift
		val destIndex = targetItem.srcIndex + shift
		debug { "${targetItem.srcIndex} $destIndex" }
		if (destIndex == targetItem.srcIndex) return
		move(targetItem.srcIndex, destIndex)
	}

	internal fun tryConsumeDrag() {
		val selectedItemIndex = selectedItemIndex
		check(selectedItemIndex != -1) { "Can only drag selected item." }

		val items = listState.layoutInfo.visibleItemsInfo

		// TODO: Need to handle reverse layout.
		val initialDragDelta = this.dragDelta

		val potentiallyOverlappingItems = if (initialDragDelta > 0) {
			// User is dragging downwards
			items.asSequence().drop(selectedItemIndex + 1)
		} else {
			// User is dragging upwards
			items.asReversed().asSequence().drop(items.size - selectedItemIndex)
		}

		var extraShift = 0
		var unconsumedDelta = abs(initialDragDelta)
		for (item in potentiallyOverlappingItems) {
			// TODO: Break loop if item is out of dnd range.
			if (item.size < unconsumedDelta) {
				unconsumedDelta -= item.size
				extraShift++
			} else {
				val fraction = unconsumedDelta / item.size
				if (fraction > 0.55f) {
					unconsumedDelta -= item.size
					extraShift++
				}
				break
			}
		}

		shift += extraShift * initialDragDelta.sign.roundToInt()
		dragDelta = unconsumedDelta * initialDragDelta.sign
	}

	fun calculateSrcIndex(destIndex: Int): Int {
		val selectedItem = this.selectedItem
		return if (selectedItem != null) {
			val shift = this.shift
			if (shift > 0) {
				// Offset into shifted range
				val localOffset = destIndex - selectedItem.srcIndex
				if (localOffset !in 0..shift) {
					destIndex
				} else if (localOffset < shift) {
					destIndex + 1
				} else {
					selectedItem.srcIndex // destIndex - shift
				}
			} else if (shift < 0) {
				if (destIndex > selectedItem.srcIndex || destIndex < selectedItem.srcIndex + shift) {
					destIndex
				} else if (destIndex > selectedItem.srcIndex + shift) {
					destIndex - 1
				} else {
					selectedItem.srcIndex // destIndex - shift
				}
			} else {
				destIndex
			}
		} else {
			destIndex
		}
	}
}

private enum class SelectState {
	Default,
	Idle,
	Shifted,
	Selected
}

fun LazyListScope.itemsWithDnd(
	state: DragAndDropState,
	count: Int,
	key: (index: Int) -> Any,
	itemContent: @Composable LazyItemScope.(index: Int) -> Unit
) {
	items(
		count,
		key = { destIndex ->
			val srcIndex = state.calculateSrcIndex(destIndex)
			key(srcIndex)
		}
	) { destIndex ->
		val srcIndex by rememberUpdatedState(state.calculateSrcIndex(destIndex))

		val selectedItem = state.selectedItem
		val transition = updateTransition(when {
			selectedItem == null -> SelectState.Default
			selectedItem.srcIndex == srcIndex -> SelectState.Selected
			srcIndex != destIndex -> SelectState.Shifted
			else -> SelectState.Idle
		})
		val elevation by transition.animateFloat { if (it == SelectState.Selected) 16f else 0f }
		val zIndex by transition.animateFloat({ snap() }) { if (it == SelectState.Selected) 1f else 0f }
		val idleTranslation by transition.animateFloat {
			if (it == SelectState.Idle) {
				0f
			} else {
				val info = state.selectedItemInfo
				if (info != null && selectedItem != null) {
					-info.size.toFloat() * (srcIndex - selectedItem.srcIndex).sign
				} else {
					0f
				}
			}
		}
		val shiftedTranslation by transition.animateFloat {
			if (it == SelectState.Shifted) {
				0f
			} else {
				val info = state.selectedItemInfo
				if (info != null && selectedItem != null) {
					info.size.toFloat() * (srcIndex - selectedItem.srcIndex).sign
				} else {
					0f
				}
			}
		}

		val dragHandle = Modifier.pointerInput(key(srcIndex)) {
			detectDragGestures(
				onDragStart = { slopPosition, position ->
					debug { "Dragging started!" }

					state.shift = 0
					state.dragDelta = slopPosition.y - position.y
					state.selectedItem = SelectedItem(srcIndex, key(srcIndex))
				},
				onDragEnd = {
					debug { "Dragging ended!" }

					state.finishDrag()
					state.selectedItem = null
				},
				onDragCancel = {
					debug { "Dragging was cancelled!" }

					state.selectedItem = null
				},
				onDrag = { change, dragAmount ->
					change.consumeAllChanges()
					state.dragDelta += dragAmount.y
					state.tryConsumeDrag()
					// Consider scrolling here
				}
			)
		}

		val modifier = Modifier.zIndex(zIndex)
			.graphicsLayer {
				translationY = when (transition.targetState) {
					SelectState.Idle -> idleTranslation
					SelectState.Shifted -> shiftedTranslation
					SelectState.Selected -> state.dragDelta
					SelectState.Default -> 0f
				}
				shadowElevation = elevation
			}

		// FIXME: Need to find a way to apply Modifier without using Box.
		//  Box changes the way items are laid out, Column would be better but LazyRow.
		Box(modifier.then(dragHandle)) {
			itemContent(srcIndex)
		}
	}
}
