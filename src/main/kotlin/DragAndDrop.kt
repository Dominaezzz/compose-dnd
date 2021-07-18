import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
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

internal data class DndItemKey(val srcIndex: Int, val originalKey: Any)

class DragAndDropState(
	private val move: (Int, Int) -> Unit,
	internal val listState: LazyListState
) {
	internal var selectedItem by mutableStateOf<DndItemKey?>(null)
	internal var clickOffset by mutableStateOf(0.0f)
	internal var currentMousePosition: Float by mutableStateOf(0.0f)

	internal val selectedItemIndex: Int by derivedStateOf {
		val selected = selectedItem
		listState.layoutInfo.visibleItemsInfo.indexOfFirst { it.key == selected }
	}

	val visibleShift: Int by derivedStateOf { calculateRenderedShift() }
	val shift: Int by derivedStateOf { visibleShift + calculatePendingShift() }

	internal fun finishDrag() {
		val targetItem = selectedItem!!
		val shift = shift
		val destIndex = targetItem.srcIndex + shift
		debug { "${targetItem.srcIndex} $destIndex" }
		if (destIndex == targetItem.srcIndex) return
		move(targetItem.srcIndex, destIndex)
	}

	private fun calculatePendingShift(): Int {
		val selectedItemIndex = selectedItemIndex
		if (selectedItemIndex == -1) return 0

		/// Calculate additional shift based on pointer position.

		val items = listState.layoutInfo.visibleItemsInfo

		// TODO: Need to handle reverse layout.
		val dragDelta = currentMousePosition - (items[selectedItemIndex].offset + clickOffset)

		val potentiallyOverlappingItems = if (dragDelta > 0) {
			// User is dragging downwards
			items.asSequence().drop(selectedItemIndex + 1)
		} else {
			// User is dragging upwards
			items.asReversed().asSequence().drop(items.size - selectedItemIndex)
		}

		var extraShift = 0
		var unconsumedDelta = abs(dragDelta)
		for (item in potentiallyOverlappingItems) {
			val itemKey = item.key
			if (itemKey !is DndItemKey) break
			if (item.size < unconsumedDelta) {
				unconsumedDelta -= item.size
				extraShift++
			} else {
				val fraction = unconsumedDelta / item.size
				if (fraction > 0.55f) {
					extraShift++
				}
				break
			}
		}

		return extraShift * sign(dragDelta).roundToInt()
	}

	private fun calculateRenderedShift(): Int {
		val selectedItemKey = selectedItem ?: return 0
		val items = listState.layoutInfo.visibleItemsInfo
		val selectedItemIndex = selectedItemIndex
		val nextItemIndex = selectedItemIndex + 1
		val prevItemIndex = selectedItemIndex - 1

		// TODO: Doesn't handle adjacent DnD scopes.

		if (prevItemIndex >= 0) {
			val prevItemKey = items[prevItemIndex].key
			if (prevItemKey is DndItemKey) {
				return if (prevItemKey.srcIndex <= selectedItemKey.srcIndex) {
					prevItemKey.srcIndex - selectedItemKey.srcIndex + 1
				} else {
					prevItemKey.srcIndex - selectedItemKey.srcIndex
				}
			}
		}
		if (nextItemIndex < items.size) {
			val nextItemKey = items[nextItemIndex].key
			if (nextItemKey is DndItemKey) {
				return if (nextItemKey.srcIndex >= selectedItemKey.srcIndex) {
					nextItemKey.srcIndex - selectedItemKey.srcIndex - 1
				} else {
					nextItemKey.srcIndex - selectedItemKey.srcIndex
				}
			}
		}

		// If this gets here, either there's only one item in the DnD scope
		// or the current visible item is bigger than the LazyList's viewport.

		// The former case should return zero and the latter should autoscroll.
		// The latter could autoscroll but is not a pragmatic use of DnD, so it
		// will stay "disabled" for now.

		return 0
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
			DndItemKey(srcIndex, key(srcIndex))
		}
	) { destIndex ->
		val srcIndex by rememberUpdatedState(state.calculateSrcIndex(destIndex))

		val selectedItem = state.selectedItem
		val elevation: Float
		val translation: Float
		val zIndex: Float

		if (selectedItem != null) {
			val selectedItemInfo = state.listState.layoutInfo.visibleItemsInfo.single {
				val itsKey = it.key
				itsKey is DndItemKey && itsKey.srcIndex == selectedItem.srcIndex
			}
			if (srcIndex == selectedItem.srcIndex) {
				elevation = 16.0f
				zIndex = 1.0f

				val initialMousePosition = selectedItemInfo.offset + state.clickOffset
				val previousFramesTranslation = state.currentMousePosition - initialMousePosition
				val lag = state.shift - state.visibleShift
				// Lag will usually be zero but we rendering based on previous frame's data, so it might not be.
				translation = if (lag == 0) {
					previousFramesTranslation
				} else {
					val selectedItemIndex = state.selectedItemIndex
					val pendingShifts = with(state.listState.layoutInfo.visibleItemsInfo) {
						if (lag > 0) {
							subList(selectedItemIndex + 1, selectedItemIndex + lag + 1)
						} else {
							subList(selectedItemIndex + lag, selectedItemIndex)
						}
					}
					val pendingTranslation = pendingShifts.sumOf { it.size } * lag.sign
					previousFramesTranslation - pendingTranslation
				}
			} else {
				elevation = 0.0f
				zIndex = 0.0f
				val animatedTranslation by animateFloatAsState(if (destIndex == srcIndex) 0.0f else 1.0f)
				val translationSize = selectedItemInfo.size.toFloat() * (srcIndex - selectedItem.srcIndex).sign
				translation = if (destIndex == srcIndex) {
					animatedTranslation * -translationSize
				} else {
					(1 - animatedTranslation) * translationSize
				}
			}
		} else {
			elevation = 0.0f
			zIndex = 0.0f
			translation = 0.0f
		}

		val pointerModifier = Modifier.pointerInput(key(srcIndex)) {
			detectDragGestures(
				onDragStart = { slopPosition, position ->
					debug { "Dragging started!" }

					val item = state.listState.layoutInfo.visibleItemsInfo.single {
						val itsKey = it.key
						itsKey is DndItemKey && itsKey.srcIndex == srcIndex
					}
					state.clickOffset = position.y
					state.currentMousePosition = item.offset + slopPosition.y
					state.selectedItem = item.key as DndItemKey
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
					change.consumePositionChange()
					state.currentMousePosition += dragAmount.y
					// Consider scrolling here
				}
			)
		}

		val animatedElevation by animateFloatAsState(elevation, spring())
		val modifier = pointerModifier
			.zIndex(zIndex)
			.graphicsLayer {
				translationY = translation
				shadowElevation = animatedElevation
			}

		// FIXME: Need to find a way to apply Modifier without using Box.
		//  Box changes the way items are laid out, Column would be better but LazyRow.
		Box(modifier) {
			itemContent(srcIndex)
		}
	}
}
