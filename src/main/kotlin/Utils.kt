import androidx.compose.foundation.lazy.LazyListItemInfo

val LazyListItemInfo.range: IntRange
	get() = offset..(offset + size)

inline fun debug(output: () -> String) { println(output()) }
