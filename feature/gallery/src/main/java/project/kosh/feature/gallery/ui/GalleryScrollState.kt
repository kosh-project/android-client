package project.kosh.feature.gallery.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Determines whether the upload FAB should be expanded.
 * The FAB must ONLY expand when at the very top of the gallery (0, 0).
 * Once scrolled into the gallery, it remains collapsed as an icon-only FAB,
 * even when the scroll pauses at rest.
 */
internal fun isGalleryFabExpanded(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean {
    return firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0
}

/**
 * Determines whether the floating three-dot island menu is visible.
 * Visibility is strictly direction-aware:
 * - Hides when scrolling down (user is browsing down through media).
 * - Shows when scrolling up, when at the top of the gallery, or when the dropdown menu is open.
 * - Stays stable at rest based on the prior scroll direction (no flicker or pop-in).
 */
internal fun isGalleryIslandVisible(
    isScrollingDown: Boolean,
    showTopMenu: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int
): Boolean {
    return !isScrollingDown || showTopMenu || (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0)
}

/**
 * Tracks scroll direction with hysteresis to eliminate UI flicker.
 *
 * Hysteresis logic:
 * - When at exact top (0, 0), scrolling down is cleared.
 * - When scrolling in progress:
 *     - Scrolling down triggers if item index increases or scroll offset increases by > 15px deadband.
 *     - Scrolling up triggers if item index decreases or scroll offset decreases by > 15px deadband.
 * - When scrolling comes to rest (inProgress == false):
 *     - Retains [isScrollingDown] state without toggling, baselining offsets for the next gesture.
 */
internal class GalleryScrollDirectionTracker(
    initialIndex: Int = 0,
    initialOffset: Int = 0
) {
    var isScrollingDown: Boolean by mutableStateOf(false)
        internal set

    var prevIndex: Int by mutableIntStateOf(initialIndex)
        internal set

    var prevScrollOffset: Int by mutableIntStateOf(initialOffset)
        internal set

    fun onScrollUpdate(index: Int, offset: Int, inProgress: Boolean) {
        if (index == 0 && offset == 0) {
            isScrollingDown = false
            prevIndex = 0
            prevScrollOffset = 0
        } else if (inProgress) {
            if (index > prevIndex || (index == prevIndex && offset > prevScrollOffset + 15)) {
                isScrollingDown = true
                prevIndex = index
                prevScrollOffset = offset
            } else if (index < prevIndex || (index == prevIndex && offset < prevScrollOffset - 15)) {
                isScrollingDown = false
                prevIndex = index
                prevScrollOffset = offset
            }
        } else {
            // When scroll comes to rest away from top, keep isScrollingDown stable to prevent flicker
            prevIndex = index
            prevScrollOffset = offset
        }
    }
}
