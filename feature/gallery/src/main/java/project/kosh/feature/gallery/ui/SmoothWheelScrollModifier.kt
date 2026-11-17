package project.kosh.feature.gallery.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Custom pointer modifier that intercepts mouse wheel scroll events and applies
 * smooth, spring-damped frame-aligned kinetic interpolation to a [LazyGridState].
 *
 * ### Architectural Mechanics
 * 1. Multiplies raw mouse wheel delta-Y by a 64f scaling coefficient to match natural touch fling inertia.
 * 2. Queues remaining scroll distance in `targetScrollDelta` and consumes 35% of the remaining
 *    distance each animation frame ([withFrameNanos]).
 * 3. Ceases dispatch once the delta is below 0.5f to prevent unnecessary continuous recomposition loops.
 */
fun Modifier.smoothWheelScroll(gridState: LazyGridState): Modifier = composed {
    val coroutineScope = rememberCoroutineScope()
    var targetScrollDelta by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Scroll) {
                    val deltaY = event.changes.fold(0f) { acc, change ->
                        acc + change.scrollDelta.y
                    } * 64f

                    if (deltaY != 0f) {
                        targetScrollDelta += deltaY
                        if (scrollJob?.isActive != true) {
                            scrollJob = coroutineScope.launch {
                                while (kotlin.math.abs(targetScrollDelta) > 0.5f) {
                                    val step = targetScrollDelta * 0.35f
                                    targetScrollDelta -= step
                                    val consumed = gridState.dispatchRawDelta(step)
                                    if (kotlin.math.abs(consumed) < 0.1f) {
                                        targetScrollDelta = 0f
                                        break
                                    }
                                    withFrameNanos { }
                                }
                                targetScrollDelta = 0f
                            }
                        }
                    }
                }
            }
        }
    }
}
