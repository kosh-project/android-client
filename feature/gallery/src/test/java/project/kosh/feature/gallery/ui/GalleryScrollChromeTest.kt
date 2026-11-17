package project.kosh.feature.gallery.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying:
 * 1. FAB expansion stability: expands ONLY at the very top (0, 0), stays collapsed anywhere else including at rest.
 * 2. Three-dot floating island visibility: direction-aware scroll tracking, flicker elimination at rest,
 *    and menu/top-of-gallery overrides.
 *
 * Directly exercises production [GalleryScrollDirectionTracker], [isGalleryFabExpanded], and [isGalleryIslandVisible].
 */
class GalleryScrollChromeTest {

    // --- FAB Expansion Stability Tests ---

    @Test
    fun fab_isExpandedAtTop() {
        assertTrue("FAB must be expanded at the exact top (0, 0)", isGalleryFabExpanded(0, 0))
    }

    @Test
    fun fab_collapsesWhenScrolledDown() {
        assertFalse("FAB must be collapsed with any positive scroll offset", isGalleryFabExpanded(0, 1))
        assertFalse("FAB must be collapsed on later items", isGalleryFabExpanded(1, 0))
        assertFalse("FAB must be collapsed deep in gallery", isGalleryFabExpanded(15, 300))
    }

    @Test
    fun fab_remainsCollapsedWhenScrollPausesAtRest() {
        // Even when scroll comes to rest (not at top), FAB must stay collapsed
        val indexAtRest = 2
        val offsetAtRest = 150
        assertFalse("FAB must remain collapsed when paused at rest", isGalleryFabExpanded(indexAtRest, offsetAtRest))
    }

    @Test
    fun fab_remainsCollapsedDuringUpwardScrollUntilExactTop() {
        // Scrolling up towards top: item 1, offset 10 -> collapsed
        assertFalse("FAB must stay collapsed while scrolling up prior to top", isGalleryFabExpanded(1, 10))
        assertFalse("FAB must stay collapsed at item 0 with non-zero offset", isGalleryFabExpanded(0, 5))
        // Reaching exact top -> expands
        assertTrue("FAB must expand once exact top (0, 0) is reached", isGalleryFabExpanded(0, 0))
    }

    // --- Floating Three-Dot Island Flicker Elimination Tests ---

    @Test
    fun island_visibleAtInitialStateAndTop() {
        val tracker = GalleryScrollDirectionTracker(0, 0)
        assertFalse(tracker.isScrollingDown)
        assertTrue(
            "Island must be visible at initial state at top",
            isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 0, 0)
        )
    }

    @Test
    fun island_hidesWhenScrollingDownPastHysteresisThreshold() {
        val tracker = GalleryScrollDirectionTracker(0, 0)

        // Sub-threshold jitter (e.g. 10px) does not flip direction
        tracker.onScrollUpdate(index = 0, offset = 10, inProgress = true)
        assertFalse(tracker.isScrollingDown)

        // Crossing 15px threshold triggers scrolling down
        tracker.onScrollUpdate(index = 0, offset = 20, inProgress = true)
        assertTrue(tracker.isScrollingDown)
        assertFalse(
            "Island must hide when scrolling down",
            isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 0, 20)
        )
    }

    @Test
    fun island_remainsHiddenWhenScrollPausesAtRest_noFlicker() {
        val tracker = GalleryScrollDirectionTracker(0, 0)

        // User scrolls down
        tracker.onScrollUpdate(index = 0, offset = 50, inProgress = true)
        assertTrue(tracker.isScrollingDown)
        assertFalse(isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 0, 50))

        // User lifts finger / pauses scroll: inProgress becomes false
        tracker.onScrollUpdate(index = 0, offset = 50, inProgress = false)

        // CRITICAL CHECK: isScrollingDown MUST remain true, island MUST remain hidden
        assertTrue("isScrollingDown must NOT reset to false when scroll rests", tracker.isScrollingDown)
        assertFalse(
            "Island must NOT pop back in or flicker when scroll pauses at rest",
            isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 0, 50)
        )

        // User resumes scrolling down: inProgress becomes true again
        tracker.onScrollUpdate(index = 0, offset = 80, inProgress = true)
        assertTrue(tracker.isScrollingDown)
        assertFalse(
            "Island must remain hidden during continued downward scroll",
            isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 0, 80)
        )
    }

    @Test
    fun island_showsWhenScrollingUpAndStaysVisibleAtRest() {
        val tracker = GalleryScrollDirectionTracker(1, 100)

        // Scroll down first
        tracker.onScrollUpdate(index = 1, offset = 150, inProgress = true)
        assertTrue(tracker.isScrollingDown)
        assertFalse(isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 1, 150))

        // Rest
        tracker.onScrollUpdate(index = 1, offset = 150, inProgress = false)
        assertTrue(tracker.isScrollingDown)

        // User scrolls up by 25px (> 15px threshold)
        tracker.onScrollUpdate(index = 1, offset = 125, inProgress = true)
        assertFalse("isScrollingDown must become false when scrolling up", tracker.isScrollingDown)
        assertTrue(
            "Island must appear when scrolling up",
            isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 1, 125)
        )

        // Scroll comes to rest while scrolling up
        tracker.onScrollUpdate(index = 1, offset = 125, inProgress = false)
        assertFalse("isScrollingDown must remain false at rest after scrolling up", tracker.isScrollingDown)
        assertTrue(
            "Island must stay visible at rest after scrolling up",
            isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 1, 125)
        )
    }

    @Test
    fun island_showsWhenCrossingItemBoundariesUpward() {
        val tracker = GalleryScrollDirectionTracker(2, 50)

        // Crossing backward across item indices (e.g. index 2 -> 1)
        tracker.onScrollUpdate(index = 1, offset = 90, inProgress = true)
        assertFalse("Crossing item backward must register as scrolling up", tracker.isScrollingDown)
        assertTrue(isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 1, 90))
    }

    @Test
    fun island_hidesWhenCrossingItemBoundariesDownward() {
        val tracker = GalleryScrollDirectionTracker(1, 90)

        // Crossing forward across item indices (e.g. index 1 -> 2)
        tracker.onScrollUpdate(index = 2, offset = 10, inProgress = true)
        assertTrue("Crossing item forward must register as scrolling down", tracker.isScrollingDown)
        assertFalse(isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 2, 10))
    }

    @Test
    fun island_alwaysVisibleWhenTopMenuIsOpen() {
        // Even if scrolling down, if the top dropdown menu is open, the island must remain visible
        val isDown = true
        assertTrue(
            "Island must remain visible when showTopMenu is true",
            isGalleryIslandVisible(isScrollingDown = isDown, showTopMenu = true, firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 200)
        )
    }

    @Test
    fun island_alwaysVisibleWhenScrolledToExactTop() {
        // Even if isScrollingDown was somehow true, top of gallery forces visibility
        assertTrue(
            "Island must always be visible at top of gallery (0, 0)",
            isGalleryIslandVisible(isScrollingDown = true, showTopMenu = false, firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
        )
    }

    @Test
    fun island_fastScrollDirectionStability() {
        // High velocity / fast scrolling up should NEVER hide the island (flicker bug fixed)
        val tracker = GalleryScrollDirectionTracker(10, 500)
        // Large jump upward: index decreases from 10 to 6
        tracker.onScrollUpdate(index = 6, offset = 200, inProgress = true)
        assertFalse(tracker.isScrollingDown)
        assertTrue(
            "Island must remain visible during fast upward scroll",
            isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 6, 200)
        )
    }

    @Test
    fun tracker_resetsDirectionWhenReachingExactTop() {
        val tracker = GalleryScrollDirectionTracker(5, 100)
        tracker.onScrollUpdate(index = 5, offset = 150, inProgress = true)
        assertTrue(tracker.isScrollingDown)

        // Programmatic jump or scroll to exact top (0, 0)
        tracker.onScrollUpdate(index = 0, offset = 0, inProgress = false)
        assertFalse("isScrollingDown must reset to false at (0, 0)", tracker.isScrollingDown)
        assertTrue(
            "Island must be visible at (0, 0)",
            isGalleryIslandVisible(tracker.isScrollingDown, showTopMenu = false, 0, 0)
        )
    }
}
