package project.kosh.feature.gallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying pull-to-refresh overstretch mechanics, spring dynamics,
 * continuous wave amplitude scaling, and indicator clearance above the gallery layout.
 */
class GalleryPullToRefreshTest {

    companion object {
        private const val INDICATOR_MAX_DISTANCE_DP = 80f
        private const val CONTENT_OVERSTRETCH_MAX_DP = 96f
        private const val MAX_OVERSTRETCH_CEILING = 1.5f
        private const val SCALE_FACTOR = 0.06f
    }

    private fun calculateIndicatorBottom(distanceFraction: Float): Float =
        distanceFraction.coerceAtLeast(0f) * INDICATOR_MAX_DISTANCE_DP

    private fun calculateContentTranslationY(distanceFraction: Float): Float =
        distanceFraction.coerceIn(0f, MAX_OVERSTRETCH_CEILING) * CONTENT_OVERSTRETCH_MAX_DP

    private fun calculateOverstretchScaleY(
        distanceFraction: Float,
        isSyncing: Boolean,
        isDismissingAfterRefresh: Boolean = false
    ): Float {
        val clampedProgress = distanceFraction.coerceIn(0f, MAX_OVERSTRETCH_CEILING)
        val relaxation = if (isSyncing || isDismissingAfterRefresh) 0f else 1f
        return 1f + (clampedProgress * SCALE_FACTOR * relaxation)
    }

    private fun isIndeterminateIndicatorActive(isRefreshing: Boolean, isDismissing: Boolean): Boolean =
        isRefreshing || isDismissing

    private fun calculateIndicatorStrokeProgress(distanceFraction: Float): Float =
        (distanceFraction.coerceIn(0f, MAX_OVERSTRETCH_CEILING) / 1.0f).coerceIn(0f, 1f)

    private fun calculateIndicatorAlpha(
        distanceFraction: Float,
        isRefreshing: Boolean,
        isDismissing: Boolean = false,
        isAnimating: Boolean = false
    ): Float {
        val clampedProgress = distanceFraction.coerceIn(0f, MAX_OVERSTRETCH_CEILING)
        return when {
            isRefreshing -> 1f
            isDismissing || isAnimating -> 0f
            clampedProgress > 0.05f -> (clampedProgress * 2.5f).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    private fun calculateWaveAmplitude(
        pullFraction: Float,
        isRefreshing: Boolean,
        isDismissing: Boolean
    ): Float = when {
        isDismissing -> 0f
        isRefreshing -> 1f
        pullFraction > 0.01f -> pullFraction.coerceIn(0f, MAX_OVERSTRETCH_CEILING)
        else -> 0f
    }

    private fun calculateWaveBarHeightDp(
        pullFraction: Float,
        isRefreshing: Boolean,
        isDismissing: Boolean
    ): Float = when {
        isDismissing -> 0f
        isRefreshing -> 16f
        pullFraction > 0.01f -> 4f + (12f * pullFraction.coerceIn(0f, MAX_OVERSTRETCH_CEILING))
        else -> 0f
    }

    private fun calculateWavyBarTopClearanceDp(isTopBarVisible: Boolean): Float =
        if (isTopBarVisible) 52f else 4f

    private fun calculateWaveCapInsets(widthPx: Float, strokeWidthPx: Float): Pair<Float, Float> {
        val capRadius = strokeWidthPx / 2f
        val startX = capRadius + 1f
        val endX = (widthPx - capRadius - 1f).coerceAtLeast(startX)
        return startX to endX
    }

    private fun calculateHermiteEnvelope(distance: Float, taperDistance: Float): Float {
        if (taperDistance <= 0f) return 1f
        val s = (distance / taperDistance).coerceIn(0f, 1f)
        return s * s * (3f - 2f * s)
    }

    @Test
    fun idleState_hasZeroDisplacementAndUnitScale() {
        assertEquals(0f, calculateIndicatorBottom(0f), 0.001f)
        assertEquals(0f, calculateContentTranslationY(0f), 0.001f)
        assertEquals(1f, calculateOverstretchScaleY(0f, isSyncing = false), 0.001f)
    }

    @Test
    fun duringPull_contentTranslationAlwaysExceedsIndicatorBottom() {
        val pullSteps = listOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f)
        for (fraction in pullSteps) {
            val indicatorBottom = calculateIndicatorBottom(fraction)
            val contentY = calculateContentTranslationY(fraction)
            assertTrue("Content top ($contentY dp) must exceed indicator bottom ($indicatorBottom dp)", contentY > indicatorBottom)
        }
    }

    @Test
    fun refreshingRestingState_providesCleanMarginAboveLayout() {
        val indicatorBottom = calculateIndicatorBottom(1.0f)
        val contentY = calculateContentTranslationY(1.0f)
        assertEquals(80f, indicatorBottom, 0.001f)
        assertEquals(96f, contentY, 0.001f)
        assertEquals(16f, contentY - indicatorBottom, 0.001f)
    }

    @Test
    fun pullOvershoot_isClampedAtCeiling() {
        val contentY = calculateContentTranslationY(2.5f)
        assertEquals(1.5f * CONTENT_OVERSTRETCH_MAX_DP, contentY, 0.001f)
        assertEquals(144f, contentY, 0.001f)
    }

    @Test
    fun elasticScale_stretchesDuringPullAndRelaxesWhenSyncingOrDismissing() {
        assertEquals(1.06f, calculateOverstretchScaleY(1.0f, isSyncing = false), 0.001f)
        assertEquals(1.09f, calculateOverstretchScaleY(1.5f, isSyncing = false), 0.001f)
        assertEquals(1.0f, calculateOverstretchScaleY(1.0f, isSyncing = true), 0.001f)
        assertEquals(1.0f, calculateOverstretchScaleY(0.8f, isSyncing = false, isDismissingAfterRefresh = true), 0.001f)
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
        assertEquals(0f, calculateIndicatorAlpha(0f, isRefreshing = false), 0.001f)
        assertEquals(0.5f, calculateIndicatorAlpha(0.2f, isRefreshing = false), 0.001f)
        assertEquals(1.0f, calculateIndicatorAlpha(1.0f, isRefreshing = false), 0.001f)
        assertEquals(1.0f, calculateIndicatorAlpha(1.0f, isRefreshing = true), 0.001f)
        assertEquals(0f, calculateIndicatorAlpha(1.0f, isRefreshing = false, isDismissing = true, isAnimating = true), 0.001f)
        assertEquals(0f, calculateIndicatorAlpha(0.5f, isRefreshing = false, isDismissing = true, isAnimating = true), 0.001f)
        assertEquals(0f, calculateIndicatorAlpha(0.5f, isRefreshing = false, isDismissing = false, isAnimating = true), 0.001f)
    }

    @Test
    fun indeterminateMode_remainsActiveThroughoutRefreshAndCollapseToPreventDeterminateReversion() {
        assertTrue(!isIndeterminateIndicatorActive(isRefreshing = false, isDismissing = false))
        assertTrue(isIndeterminateIndicatorActive(isRefreshing = true, isDismissing = false))
        assertTrue(isIndeterminateIndicatorActive(isRefreshing = false, isDismissing = true))
    }

    @Test
    fun waveAmplitude_scalesLinearlyFromFlatToMaxDuringPull() {
        assertEquals(0f, calculateWaveAmplitude(0f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(0.25f, calculateWaveAmplitude(0.25f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(0.5f, calculateWaveAmplitude(0.5f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(0.75f, calculateWaveAmplitude(0.75f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(1.0f, calculateWaveAmplitude(1.0f, isRefreshing = false, isDismissing = false), 0.001f)
        // Overshoot scales amplitude proportionally beyond 1.0f up to MAX_OVERSTRETCH_CEILING (1.5f)
        assertEquals(1.25f, calculateWaveAmplitude(1.25f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(1.5f, calculateWaveAmplitude(1.5f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(1.5f, calculateWaveAmplitude(2.5f, isRefreshing = false, isDismissing = false), 0.001f)
    }

    @Test
    fun waveAmplitude_holdsAtMaxDuringRefresh_flattensOnDismissal() {
        assertEquals(1.0f, calculateWaveAmplitude(0f, isRefreshing = true, isDismissing = false), 0.001f)
        assertEquals(0f, calculateWaveAmplitude(1.0f, isRefreshing = true, isDismissing = true), 0.001f)
    }

    @Test
    fun barHeight_bloomsProportionallyWithPull_andShrinksToZeroOnDismissal() {
        assertEquals(0f, calculateWaveBarHeightDp(0f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(4f, calculateWaveBarHeightDp(0.015f, isRefreshing = false, isDismissing = false), 0.2f)
        assertEquals(10f, calculateWaveBarHeightDp(0.5f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(16f, calculateWaveBarHeightDp(1.0f, isRefreshing = false, isDismissing = false), 0.001f)
        // Overstretch beyond threshold expands bar height up to 22dp at 1.5 ceiling
        assertEquals(19f, calculateWaveBarHeightDp(1.25f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(22f, calculateWaveBarHeightDp(1.5f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(22f, calculateWaveBarHeightDp(2.5f, isRefreshing = false, isDismissing = false), 0.001f)
        // Active refresh: held at 16dp
        assertEquals(16f, calculateWaveBarHeightDp(0f, isRefreshing = true, isDismissing = false), 0.001f)
        // Dismissal: shrinks to 0dp
        assertEquals(0f, calculateWaveBarHeightDp(1.0f, isRefreshing = true, isDismissing = true), 0.001f)
    }

    @Test
    fun overstretchCoupling_waveAmplitudeAndBarHeightScaleBeyondUnitCeiling() {
        // At 1.0f (threshold), amplitude is 1.0 and height is 16dp
        assertEquals(1.0f, calculateWaveAmplitude(1.0f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(16f, calculateWaveBarHeightDp(1.0f, isRefreshing = false, isDismissing = false), 0.001f)
        // At 1.5f (max overstretch), amplitude scales to 1.5 and height expands to 22dp
        assertEquals(1.5f, calculateWaveAmplitude(1.5f, isRefreshing = false, isDismissing = false), 0.001f)
        assertEquals(22f, calculateWaveBarHeightDp(1.5f, isRefreshing = false, isDismissing = false), 0.001f)
        // Upon release to active refresh, amplitude relaxes back to 1.0 and height to 16dp
        assertEquals(1.0f, calculateWaveAmplitude(1.0f, isRefreshing = true, isDismissing = false), 0.001f)
        assertEquals(16f, calculateWaveBarHeightDp(1.0f, isRefreshing = true, isDismissing = false), 0.001f)
    }

    @Test
    fun wavyProgressBarClearance_avoidsOverlapWithTopIslandBar() {
        val clearanceWithIsland = calculateWavyBarTopClearanceDp(isTopBarVisible = true)
        assertTrue("Wavy bar top clearance ($clearanceWithIsland dp) must exceed island bottom (48 dp)", clearanceWithIsland >= 52f)
        val clearanceScrolled = calculateWavyBarTopClearanceDp(isTopBarVisible = false)
        assertEquals(4f, clearanceScrolled, 0.001f)
    }

    @Test
    fun waveCapInsets_guaranteeFullSemicircularCapsWithoutViewportClipping() {
        val widthPx = 800f
        val strokeWidthPx = 12f
        val capRadius = strokeWidthPx / 2f
        val (startX, endX) = calculateWaveCapInsets(widthPx, strokeWidthPx)

        // Left cap starts at startX - capRadius, which must be >= 0 (no clipping on left boundary)
        assertTrue("Left cap boundary must not be clipped", startX - capRadius >= 0f)
        // Right cap ends at endX + capRadius, which must be <= widthPx (no clipping on right boundary)
        assertTrue("Right cap boundary must not be clipped", endX + capRadius <= widthPx)
    }

    @Test
    fun hermiteEnvelope_anchorsEndpointsToCenterline_andReachesFullUnitWaveInBody() {
        val taperDistance = 40f
        // At endpoint (distance 0), envelope is strictly 0.0f (anchored to centerline)
        assertEquals(0f, calculateHermiteEnvelope(0f, taperDistance), 0.001f)
        // Mid-taper rises smoothly
        assertEquals(0.5f, calculateHermiteEnvelope(20f, taperDistance), 0.001f)
        // At and beyond taper distance, envelope reaches 1.0f (full expressive wave)
        assertEquals(1.0f, calculateHermiteEnvelope(40f, taperDistance), 0.001f)
        assertEquals(1.0f, calculateHermiteEnvelope(100f, taperDistance), 0.001f)
    }
}
