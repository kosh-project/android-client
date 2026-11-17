package project.kosh.feature.gallery.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Custom pointer modifier detecting multi-touch pinch gestures to dynamically adjust
 * the column count of a grid between [minColumns] and [maxColumns].
 *
 * ### Architectural Mechanics & Clamping Logic
 * - Spreading two fingers past the 1.25f threshold zooms in (decreases column count to enlarge tiles).
 * - Pinching two fingers inward past the 0.80f threshold zooms out (increases column count to shrink tiles).
 * - Clamps column count within [[minColumns], [maxColumns]] (typically 1 to 5).
 * - Whenever the active pointer count drops below 2, accumulated zoom resets to 1.0f to avoid jitter.
 *
 * @param columnCount Current active number of columns.
 * @param minColumns Minimum allowed column count (default 1).
 * @param maxColumns Maximum allowed column count (default 5).
 * @param onColumnsChange Callback invoked when the gesture triggers a column transition.
 */
fun Modifier.pinchZoomGridColumns(
    columnCount: Int,
    minColumns: Int = 1,
    maxColumns: Int = 5,
    onColumnsChange: (Int) -> Unit
): Modifier = pointerInput(columnCount, minColumns, maxColumns) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulatedZoom = 1f
        do {
            val event = awaitPointerEvent()
            if (event.changes.size > 1) {
                val zoom = event.calculateZoom()
                accumulatedZoom *= zoom
                if (accumulatedZoom > 1.25f) {
                    if (columnCount > minColumns) {
                        onColumnsChange(columnCount - 1)
                    }
                    accumulatedZoom = 1f
                    event.changes.forEach { it.consume() }
                } else if (accumulatedZoom < 0.80f) {
                    if (columnCount < maxColumns) {
                        onColumnsChange(columnCount + 1)
                    }
                    accumulatedZoom = 1f
                    event.changes.forEach { it.consume() }
                }
            } else {
                accumulatedZoom = 1f
            }
        } while (event.changes.any { it.pressed })
    }
}
