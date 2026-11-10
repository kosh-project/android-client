package project.kosh.feature.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Test
import project.kosh.core.model.UploadStage

class BatchUploadStateTest {

    @Test
    fun defaultState_hasZeroProgressAndIdleProperties() {
        val state = BatchUploadState()
        assertEquals(0, state.totalItems)
        assertEquals(0, state.completedItems)
        assertEquals(0f, state.overallProgress, 0.0001f)
        assertEquals("Syncing...", state.stageDescription)
    }

    @Test
    fun overallProgress_calculatesCorrectWeightsAcrossPipelineStages() {
        val total = 2

        // Stage 1: Hashing at 50%
        val hashingState = BatchUploadState(
            totalItems = total,
            completedItems = 0,
            currentStage = UploadStage.Hashing(0.5f)
        )
        // 0/2 + (0.5 * 0.3) / 2 = 0.075
        assertEquals(0.075f, hashingState.overallProgress, 0.0001f)
        assertEquals("Hashing (50%)", hashingState.stageDescription)

        // Stage 2: Generating Thumbnail
        val thumbState = BatchUploadState(
            totalItems = total,
            completedItems = 0,
            currentStage = UploadStage.GeneratingThumbnail
        )
        // 0/2 + (0.35) / 2 = 0.175
        assertEquals(0.175f, thumbState.overallProgress, 0.0001f)
        assertEquals("Generating thumbnail...", thumbState.stageDescription)

        // Stage 3: Encrypting
        val encryptState = BatchUploadState(
            totalItems = total,
            completedItems = 0,
            currentStage = UploadStage.Encrypting
        )
        // 0/2 + (0.40) / 2 = 0.20
        assertEquals(0.20f, encryptState.overallProgress, 0.0001f)
        assertEquals("Encrypting with AES-256-GCM...", encryptState.stageDescription)

        // Stage 4: Uploading item 1 at 50%
        val uploadState = BatchUploadState(
            totalItems = total,
            completedItems = 0,
            currentStage = UploadStage.Uploading(
                progress = 0.5f,
                bytesSent = 5 * 1024 * 1024L,
                totalBytes = 10 * 1024 * 1024L
            )
        )
        // 0/2 + (0.40 + 0.5 * 0.55) / 2 = (0.40 + 0.275) / 2 = 0.3375
        assertEquals(0.3375f, uploadState.overallProgress, 0.0001f)
        assertEquals("Streaming to CAS: 5.0 MB / 10.0 MB", uploadState.stageDescription)

        // Stage 5: Finalizing item 1
        val finalState = BatchUploadState(
            totalItems = total,
            completedItems = 0,
            currentStage = UploadStage.Finalizing
        )
        // 0/2 + (0.98) / 2 = 0.49
        assertEquals(0.49f, finalState.overallProgress, 0.0001f)
        assertEquals("Recording in ledger...", finalState.stageDescription)

        // Item 1 completed: exactly 1 of 2 items finished (must NOT jump to 1.0f)
        val item1DoneState = BatchUploadState(
            totalItems = total,
            completedItems = 1,
            currentStage = UploadStage.Completed
        )
        assertEquals(0.5f, item1DoneState.overallProgress, 0.0001f)
        assertEquals("Done", item1DoneState.stageDescription)

        // Item 2 starts hashing at 50%
        val item2HashingState = BatchUploadState(
            totalItems = total,
            completedItems = 1,
            currentStage = UploadStage.Hashing(0.5f)
        )
        // 1/2 + (0.5 * 0.3) / 2 = 0.5 + 0.075 = 0.575
        assertEquals(0.575f, item2HashingState.overallProgress, 0.0001f)

        // Both items completed
        val allDoneState = BatchUploadState(
            totalItems = total,
            completedItems = 2,
            currentStage = UploadStage.Completed
        )
        assertEquals(1.0f, allDoneState.overallProgress, 0.0001f)
        assertEquals("Done", allDoneState.stageDescription)
    }

    @Test
    fun stageDescription_formatsUploadingWithSpeedAccurately() {
        val state = BatchUploadState(
            totalItems = 1,
            completedItems = 0,
            currentStage = UploadStage.Uploading(
                progress = 0.6f,
                bytesSent = 6 * 1024 * 1024L,
                totalBytes = 10 * 1024 * 1024L,
                speedBytesPerSec = (2.5 * 1024 * 1024).toLong()
            )
        )
        assertEquals("Streaming to CAS: 6.0 MB / 10.0 MB (2.5 MB/s)", state.stageDescription)
    }

    @Test
    fun stageDescription_formatsFailedStageAccurately() {
        val state = BatchUploadState(
            totalItems = 1,
            completedItems = 0,
            currentStage = UploadStage.Failed("Connection refused")
        )
        assertEquals("Failed: Connection refused", state.stageDescription)
        assertEquals(0f, state.overallProgress, 0.0001f)
    }
}
