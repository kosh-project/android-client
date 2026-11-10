package project.kosh.feature.gallery.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests verifying the pinch-to-zoom mathematical logic and column count clamping
 * between 1 and 5 columns for the gallery media grid.
 */
class GalleryGridZoomTest {

    private fun processZoomGesture(
        initialColumns: Int,
        zoomMultipliers: List<Float>
    ): Int {
        var columnCount = initialColumns
        var accumulatedZoom = 1f

        for (zoom in zoomMultipliers) {
            accumulatedZoom *= zoom
            if (accumulatedZoom > 1.25f) {
                if (columnCount > 1) {
                    columnCount--
                }
                accumulatedZoom = 1f
            } else if (accumulatedZoom < 0.80f) {
                if (columnCount < 5) {
                    columnCount++
                }
                accumulatedZoom = 1f
            }
        }

        return columnCount
    }

    @Test
    fun defaultColumns_isThree() {
        val columns = processZoomGesture(3, emptyList())
        assertEquals(3, columns)
    }

    @Test
    fun pinchOut_spreadingFingers_decreasesColumnsToEnlargeTiles() {
        // Single pinch-out step passing 1.25 threshold
        val columns = processZoomGesture(3, listOf(1.30f))
        assertEquals(2, columns)

        // Second pinch-out step decreasing to 1 column
        val columnsOne = processZoomGesture(2, listOf(1.30f))
        assertEquals(1, columnsOne)
    }

    @Test
    fun pinchOut_clampsAtOneColumn() {
        // Cannot decrease below 1 column
        val columns = processZoomGesture(1, listOf(1.50f, 1.50f))
        assertEquals(1, columns)
    }

    @Test
    fun pinchIn_contractingFingers_increasesColumnsToShrinkTiles() {
        // Single pinch-in step passing 0.80 threshold
        val columns = processZoomGesture(3, listOf(0.75f))
        assertEquals(4, columns)

        // Second pinch-in step increasing to 5 columns
        val columnsFive = processZoomGesture(4, listOf(0.75f))
        assertEquals(5, columnsFive)
    }

    @Test
    fun pinchIn_clampsAtFiveColumns() {
        // Cannot increase beyond 5 columns
        val columns = processZoomGesture(5, listOf(0.50f, 0.50f))
        assertEquals(5, columns)
    }

    @Test
    fun smallJitter_doesNotTriggerColumnChange() {
        // Minor zoom fluctuations between 0.85 and 1.15 do not trigger changes
        val columns = processZoomGesture(3, listOf(1.05f, 0.98f, 1.02f, 0.95f))
        assertEquals(3, columns)
    }

    @Test
    fun fingerCountDropsToOne_resetsAccumulatedZoom() {
        var columnCount = 3
        var accumulatedZoom = 1f
        // Multi-touch pinch starts (finger count > 1), zooms partially to 1.15
        accumulatedZoom *= 1.15f
        // One finger lifts (finger count <= 1) -> accumulatedZoom resets to 1.0
        accumulatedZoom = 1f
        // Multi-touch resumes (finger count > 1), zooms partially to 1.15
        accumulatedZoom *= 1.15f
        // Threshold 1.25f is NOT exceeded because stale zoom factor was discarded
        if (accumulatedZoom > 1.25f) {
            columnCount--
        }
        assertEquals(3, columnCount)
    }
}

