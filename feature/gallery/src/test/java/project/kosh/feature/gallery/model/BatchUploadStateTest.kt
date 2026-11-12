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

    @Test
    fun overallProgress_withTotalBatchBytes_calculatesContinuousByteLevelProgressForLargeSingleFile() {
        val fourGb = 4L * 1024L * 1024L * 1024L

        // Single 4GB video uploading at 1GB (25%)
        val state1 = BatchUploadState(
            totalItems = 1,
            completedItems = 0,
            totalBatchBytes = fourGb,
            completedBatchBytes = 0L,
            currentStage = UploadStage.Uploading(
                progress = 0.25f,
                bytesSent = 1L * 1024L * 1024L * 1024L,
                totalBytes = fourGb
            )
        )
        assertEquals(0.25f, state1.overallProgress, 0.0001f)

        // Uploading at 2GB (50%)
        val state2 = state1.copy(
            currentStage = UploadStage.Uploading(
                progress = 0.50f,
                bytesSent = 2L * 1024L * 1024L * 1024L,
                totalBytes = fourGb
            )
        )
        assertEquals(0.50f, state2.overallProgress, 0.0001f)

        // Uploading at 3GB (75%)
        val state3 = state1.copy(
            currentStage = UploadStage.Uploading(
                progress = 0.75f,
                bytesSent = 3L * 1024L * 1024L * 1024L,
                totalBytes = fourGb
            )
        )
        assertEquals(0.75f, state3.overallProgress, 0.0001f)

        // Upload finished, now Finalizing in ledger (must NOT drop to 0f!)
        val stateFinalizing = state1.copy(
            currentItemBytesSent = fourGb,
            currentStage = UploadStage.Finalizing
        )
        assertEquals(1.0f, stateFinalizing.overallProgress, 0.0001f)

        // Completed (100%)
        val state4 = state1.copy(
            completedItems = 1,
            completedBatchBytes = fourGb,
            currentStage = UploadStage.Completed
        )
        assertEquals(1.0f, state4.overallProgress, 0.0001f)
    }

    @Test
    fun overallProgress_withTotalBatchBytes_tracksMultiFileBatchSmoothly() {
        val totalBytes = 500L * 1024L * 1024L // 500 MB total (file1: 100MB, file2: 400MB)

        // File 1 finished (100MB done), File 2 uploading at 200MB out of 400MB (total 300MB done = 60%)
        val state = BatchUploadState(
            totalItems = 2,
            completedItems = 1,
            totalBatchBytes = totalBytes,
            completedBatchBytes = 100L * 1024L * 1024L,
            currentStage = UploadStage.Uploading(
                progress = 0.5f,
                bytesSent = 200L * 1024L * 1024L,
                totalBytes = 400L * 1024L * 1024L
            )
        )
        assertEquals(0.60f, state.overallProgress, 0.0001f)
    }
}

