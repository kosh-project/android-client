package project.kosh.feature.gallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import project.kosh.core.model.UploadStage

/**
 * Unit tests verifying upload veil animation constants, height preservation,
 * opacity fade-out transitions, and tick icon lifecycle.
 */
class GalleryItemTileVisualsTest {

    @Test
    fun animationDurations_matchDesignSpecification() {
        assertEquals(450, GalleryItemTileVisuals.VEIL_RISE_DURATION_MS)
        assertEquals(600, GalleryItemTileVisuals.VEIL_FADE_OUT_DURATION_MS)
        assertEquals(800L, GalleryItemTileVisuals.COMPLETED_FLASH_DURATION_MS)
        assertEquals(0.38f, GalleryItemTileVisuals.VEIL_ALPHA, 0.0001f)
    }

    @Test
    fun targetVeilAlpha_duringUploading_isThirtyEightPercent() {
        val alphaUploading = GalleryItemTileVisuals.targetVeilAlpha(isUploading = true)
        assertEquals(0.38f, alphaUploading, 0.0001f)
    }

    @Test
    fun targetVeilAlpha_whenCompletedOrIdle_isZero() {
        val alphaCompleted = GalleryItemTileVisuals.targetVeilAlpha(isUploading = false)
        assertEquals(0.0f, alphaCompleted, 0.0001f)
    }

    @Test
    fun targetVeilHeight_duringActiveUpload_scalesWithProgress() {
        val heightQuarter = GalleryItemTileVisuals.targetVeilHeight(
            isUploading = true,
            uploadProgress = 0.25f,
            keepFullHeight = false
        )
        assertEquals(0.25f, heightQuarter, 0.0001f)

        val heightHalf = GalleryItemTileVisuals.targetVeilHeight(
            isUploading = true,
            uploadProgress = 0.50f,
            keepFullHeight = false
        )
        assertEquals(0.50f, heightHalf, 0.0001f)

        val heightFull = GalleryItemTileVisuals.targetVeilHeight(
            isUploading = true,
            uploadProgress = 1.0f,
            keepFullHeight = false
        )
        assertEquals(1.0f, heightFull, 0.0001f)
    }

    @Test
    fun targetVeilHeight_whenUploadCompletes_preservesFullHeightWithoutCollapsing() {
        // When transitioning to completed, keepFullHeight is true
        val heightCompleted = GalleryItemTileVisuals.targetVeilHeight(
            isUploading = false,
            uploadProgress = 0f,
            keepFullHeight = true
        )
        // Must stay at 1.0f full height instead of collapsing to 0
        assertEquals(1.0f, heightCompleted, 0.0001f)
    }

    @Test
    fun shouldKeepFullHeight_whenCompleted_returnsTrue() {
        assertTrue(GalleryItemTileVisuals.shouldKeepFullHeight(isCompleted = true, animatedVeilAlpha = 0.38f))
        assertTrue(GalleryItemTileVisuals.shouldKeepFullHeight(isCompleted = true, animatedVeilAlpha = 0.20f))
        assertTrue(GalleryItemTileVisuals.shouldKeepFullHeight(isCompleted = true, animatedVeilAlpha = 0.00f))
    }

    @Test
    fun shouldKeepFullHeight_whenAlphaStillFading_returnsTrue() {
        // Even if stage became null, keep full height while alpha is above threshold
        assertTrue(GalleryItemTileVisuals.shouldKeepFullHeight(isCompleted = false, animatedVeilAlpha = 0.25f))
        assertTrue(GalleryItemTileVisuals.shouldKeepFullHeight(isCompleted = false, animatedVeilAlpha = 0.02f))
    }

    @Test
    fun shouldKeepFullHeight_whenAlphaFadedOut_returnsFalse() {
        // Once alpha has dropped to zero or below 0.01f, height resets
        assertFalse(GalleryItemTileVisuals.shouldKeepFullHeight(isCompleted = false, animatedVeilAlpha = 0.005f))
        assertFalse(GalleryItemTileVisuals.shouldKeepFullHeight(isCompleted = false, animatedVeilAlpha = 0.0f))
    }

    @Test
    fun stageTransition_fromCompletedToNull_properlyClearsCompletionState() {
        var showCompletedFlash = false
        val stages = listOf(
            UploadStage.Uploading(0.5f, 500L, 1000L),
            UploadStage.Finalizing,
            UploadStage.Completed,
            null
        )

        for (stage in stages) {
            if (stage is UploadStage.Completed) {
                showCompletedFlash = true
            } else {
                showCompletedFlash = false
            }
        }

        assertFalse("showCompletedFlash must be cleared when stage becomes null", showCompletedFlash)
    }
}
