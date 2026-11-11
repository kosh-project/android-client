package project.kosh.feature.gallery.model

import project.kosh.core.model.UploadStage

/**
 * State snapshot describing aggregate progress across a queued batch of media uploads.
 */
data class BatchUploadState(
    val totalItems: Int = 0,
    val completedItems: Int = 0,
    val currentItemIndex: Int = 0,
    val currentFileName: String? = null,
    val currentStage: UploadStage? = null,
    val isUploading: Boolean = false
) {
    /**
     * Continuous 0.0 to 1.0 fraction representing aggregate batch completion.
     */
    val overallProgress: Float
        get() = if (totalItems > 0) {
            val itemWeight = 1f / totalItems
            val baseProgress = completedItems.toFloat() / totalItems
            val currentItemPart = when (val s = currentStage) {
                is UploadStage.Hashing -> s.progress * 0.3f
                is UploadStage.GeneratingThumbnail -> 0.35f
                is UploadStage.Encrypting -> 0.40f
                is UploadStage.Uploading -> 0.40f + (s.progress * 0.55f)
                is UploadStage.Finalizing -> 0.98f
                else -> 0f
            }
            (baseProgress + currentItemPart * itemWeight).coerceIn(0f, 1f)
        } else 0f

    /**
     * User-facing text describing the active pipeline operation.
     */
    val stageDescription: String
        get() = when (val s = currentStage) {
            is UploadStage.Hashing -> "Hashing (${(s.progress * 100).toInt()}%)"
            is UploadStage.GeneratingThumbnail -> "Generating thumbnail..."
            is UploadStage.Encrypting -> "Encrypting with AES-256-GCM..."
            is UploadStage.Uploading -> {
                val sentMb = s.bytesSent / (1024.0 * 1024.0)
                val totalMb = s.totalBytes / (1024.0 * 1024.0)
                if (s.speedBytesPerSec > 0) {
                    val speedMb = s.speedBytesPerSec / (1024.0 * 1024.0)
                    String.format(java.util.Locale.US, "Streaming to CAS: %.1f MB / %.1f MB (%.1f MB/s)", sentMb, totalMb, speedMb)
                } else {
                    String.format(java.util.Locale.US, "Streaming to CAS: %.1f MB / %.1f MB", sentMb, totalMb)
                }
            }
            is UploadStage.Finalizing -> "Recording in ledger..."
            is UploadStage.Completed -> "Done"
            is UploadStage.Failed -> "Failed: ${s.error}"
            null -> "Syncing..."
        }
}
