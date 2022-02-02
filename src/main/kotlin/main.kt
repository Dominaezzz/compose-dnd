import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Text
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.singleWindowApplication

data class Item(
	val id: Int,
	val padding: Dp,
	val color: Color = when (id % 3) {
		0 -> Color.Cyan
		1 -> Color.Red
		2 -> Color.Green
		else -> Color.Gray
	}
)

const val ITEM_COUNT = 20

fun main() {
	val someItems = List (ITEM_COUNT) { Item(it, 60.dp) }.toMutableStateList()
	// val someItems = List (ITEM_COUNT) { Item(it, ((it + 1) * 5).dp) }.toMutableStateList()
	// val someItems = List (ITEM_COUNT) { Item(it, ((ITEM_COUNT - it + 1) * 5).dp) }.toMutableStateList()

	singleWindowApplication {
		MaterialTheme {
			val listState = rememberLazyListState()

			val dndState = remember(listState) {
				DragAndDropState(
					{ from, to -> someItems.move(from, to) },
					listState
				)
			}

			LazyColumn(state = listState) {
				itemsWithDnd(
					dndState,
					someItems.size, key = { someItems[it].id }
				) { itemIdx ->
					val item = someItems[itemIdx]
					Text(
						"This is item number ${item.id + 1}.",
						Modifier
							.dragAnimations()
							.dragHandle()
							.background(item.color)
							.padding(item.padding),
						textAlign = TextAlign.Center
					)
					// Divider()
				}
			}
		}
	}
}

fun <T> MutableList<T>.move(fromIdx: Int, toIdx: Int) {
	if (fromIdx == toIdx) return

	val value = removeAt(fromIdx)
	add(toIdx, value)
}
