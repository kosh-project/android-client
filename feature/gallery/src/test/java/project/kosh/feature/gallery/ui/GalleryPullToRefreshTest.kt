package project.kosh.feature.gallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying pull-to-refresh overstretch mechanics, spring dynamics,
 * and Material 3 loading indicator clearance above the gallery layout.
 */
class GalleryPullToRefreshTest {

    companion object {
        private const val INDICATOR_MAX_DISTANCE_DP = 80f
        private const val CONTENT_OVERSTRETCH_MAX_DP = 96f
        private const val MAX_OVERSTRETCH_CEILING = 1.5f
        private const val SCALE_FACTOR = 0.06f
    }

    private fun calculateIndicatorBottom(distanceFraction: Float): Float {
        // In Material 3 IndicatorBox: translationY = distanceFraction * maxDistance - size.height.
        // The container height is 40dp, so bottom edge is translationY + 40dp = distanceFraction * maxDistance.
        return distanceFraction.coerceAtLeast(0f) * INDICATOR_MAX_DISTANCE_DP
    }

    private fun calculateContentTranslationY(distanceFraction: Float): Float {
        val clampedProgress = distanceFraction.coerceIn(0f, MAX_OVERSTRETCH_CEILING)
        return clampedProgress * CONTENT_OVERSTRETCH_MAX_DP
    }

    private fun calculateOverstretchScaleY(
        distanceFraction: Float,
        isSyncing: Boolean,
        isDismissingAfterRefresh: Boolean = false
    ): Float {
        val clampedProgress = distanceFraction.coerceIn(0f, MAX_OVERSTRETCH_CEILING)
        val relaxation = if (isSyncing || isDismissingAfterRefresh) 0f else 1f
        return 1f + (clampedProgress * SCALE_FACTOR * relaxation)
    }

    private fun isIndeterminateIndicatorActive(
        isRefreshing: Boolean,
        isDismissingAfterRefresh: Boolean
    ): Boolean {
        return isRefreshing || isDismissingAfterRefresh
    }

    @Test
    fun idleState_hasZeroDisplacementAndUnitScale() {
        val distanceFraction = 0f
        val indicatorBottom = calculateIndicatorBottom(distanceFraction)
        val contentY = calculateContentTranslationY(distanceFraction)
        val scaleY = calculateOverstretchScaleY(distanceFraction, isSyncing = false)

        assertEquals(0f, indicatorBottom, 0.001f)
        assertEquals(0f, contentY, 0.001f)
        assertEquals(1f, scaleY, 0.001f)
    }

    @Test
    fun duringPull_contentTranslationAlwaysExceedsIndicatorBottom() {
        // At all pull fractions > 0, the content layout must sit strictly below the indicator bottom,
        // ensuring the M3 loading indicator floats above the layout without occluding grid tiles.
        val pullSteps = listOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f)
        for (fraction in pullSteps) {
            val indicatorBottom = calculateIndicatorBottom(fraction)
            val contentY = calculateContentTranslationY(fraction)

            assertTrue(
                "Content top ($contentY dp) must be strictly greater than indicator bottom ($indicatorBottom dp) at fraction $fraction",
                contentY > indicatorBottom
            )
        }
    }

    @Test
    fun refreshingRestingState_providesCleanMarginAboveLayout() {
        // At threshold (distanceFraction = 1.0), indicator bottom is at 80dp.
        // Content rests at 96dp, providing exactly 16dp of breathing space above the grid.
        val distanceFraction = 1.0f
        val indicatorBottom = calculateIndicatorBottom(distanceFraction)
        val contentY = calculateContentTranslationY(distanceFraction)
        val clearance = contentY - indicatorBottom

        assertEquals(80f, indicatorBottom, 0.001f)
        assertEquals(96f, contentY, 0.001f)
        assertEquals(16f, clearance, 0.001f)
    }

    @Test
    fun pullOvershoot_isClampedAtCeiling() {
        // Gestures beyond 1.5 ceiling (e.g. 2.0 or 3.0) clamp to prevent excessive downward translation
        val extremeFraction = 2.5f
        val contentY = calculateContentTranslationY(extremeFraction)
        val expectedClampedY = 1.5f * CONTENT_OVERSTRETCH_MAX_DP

        assertEquals(expectedClampedY, contentY, 0.001f)
        assertEquals(144f, contentY, 0.001f)
    }

    @Test
    fun elasticScale_stretchesDuringPullAndRelaxesWhenSyncingOrDismissing() {
        // Pulling to threshold stretches vertically to 1.06x
        val pullScale = calculateOverstretchScaleY(1.0f, isSyncing = false)
        assertEquals(1.06f, pullScale, 0.001f)

        // Overshoot to 1.5 ceiling stretches to 1.09x
        val maxPullScale = calculateOverstretchScaleY(1.5f, isSyncing = false)
        assertEquals(1.09f, maxPullScale, 0.001f)

        // Once sync is triggered, scale relaxes back to 1.0x while translation offset holds
        val syncingScale = calculateOverstretchScaleY(1.0f, isSyncing = true)
        assertEquals(1.0f, syncingScale, 0.001f)

        // During post-refresh dismissal collapse, scale also holds at 1.0x (no stretch bounce)
        val dismissingScale = calculateOverstretchScaleY(0.8f, isSyncing = false, isDismissingAfterRefresh = true)
        assertEquals(1.0f, dismissingScale, 0.001f)
    }

    private fun calculateIndicatorStrokeProgress(distanceFraction: Float): Float {
        val clampedProgress = distanceFraction.coerceIn(0f, MAX_OVERSTRETCH_CEILING)
        return (clampedProgress / 1.0f).coerceIn(0f, 1f)
    }

    private fun calculateIndicatorAlpha(
        distanceFraction: Float,
        isRefreshing: Boolean,
        isDismissingAfterRefresh: Boolean = false,
        isAnimating: Boolean = false
    ): Float {
        val clampedProgress = distanceFraction.coerceIn(0f, MAX_OVERSTRETCH_CEILING)
        return when {
            isRefreshing -> 1f
            isDismissingAfterRefresh -> 0f
            isAnimating -> 0f
            clampedProgress > 0.05f -> (clampedProgress * 2.5f).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    @Test
    fun pullDown_drawsStrokeProgressProportionallyWithStretch() {
        assertEquals(0f, calculateIndicatorStrokeProgress(0f), 0.001f)
        assertEquals(0.25f, calculateIndicatorStrokeProgress(0.25f), 0.001f)
        assertEquals(0.5f, calculateIndicatorStrokeProgress(0.5f), 0.001f)
        assertEquals(0.75f, calculateIndicatorStrokeProgress(0.75f), 0.001f)
        assertEquals(1.0f, calculateIndicatorStrokeProgress(1.0f), 0.001f)
        assertEquals(1.0f, calculateIndicatorStrokeProgress(1.5f), 0.001f)
    }

    @Test
    fun indicatorAlpha_fadesInDuringPull_holdsDuringRefresh_fadesOutOnCollapse() {
        // Resting: fully transparent
        assertEquals(0f, calculateIndicatorAlpha(0f, isRefreshing = false), 0.001f)

        // Pull down 0.2: smoothly faded in (0.2 * 2.5 = 0.5)
        assertEquals(0.5f, calculateIndicatorAlpha(0.2f, isRefreshing = false), 0.001f)

        // Threshold reached (1.0): fully visible
        assertEquals(1.0f, calculateIndicatorAlpha(1.0f, isRefreshing = false), 0.001f)

        // In refresh mode: held at 1.0
        assertEquals(1.0f, calculateIndicatorAlpha(1.0f, isRefreshing = true), 0.001f)

        // Exactly when refresh ends at 1.0f: immediately targets 0.0f fade out
        assertEquals(0f, calculateIndicatorAlpha(1.0f, isRefreshing = false, isDismissingAfterRefresh = true, isAnimating = true), 0.001f)

        // Mid-collapse at 0.5f: still targets 0.0f
        assertEquals(0f, calculateIndicatorAlpha(0.5f, isRefreshing = false, isDismissingAfterRefresh = true, isAnimating = true), 0.001f)

        // User releases before threshold without refreshing (cancelled pull): immediately fades out
        assertEquals(0f, calculateIndicatorAlpha(0.5f, isRefreshing = false, isDismissingAfterRefresh = false, isAnimating = true), 0.001f)
    }

    @Test
    fun indeterminateMode_remainsActiveThroughoutRefreshAndCollapseToPreventDeterminateReversion() {
        // Pulling down: not indeterminate mode (uses determinate arc)
        assertTrue(!isIndeterminateIndicatorActive(isRefreshing = false, isDismissingAfterRefresh = false))

        // Active refresh: indeterminate spinner active
        assertTrue(isIndeterminateIndicatorActive(isRefreshing = true, isDismissingAfterRefresh = false))

        // Post-refresh collapse: indeterminate spinner remains active while fading out
        assertTrue(isIndeterminateIndicatorActive(isRefreshing = false, isDismissingAfterRefresh = true))
    }
}
