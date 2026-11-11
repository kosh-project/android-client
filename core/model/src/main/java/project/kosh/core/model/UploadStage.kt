package project.kosh.core.model

/**
 * Observable stages of zero-knowledge media ingestion and synchronization.
 */
sealed interface UploadStage {
    /** Hardware-accelerated Conscrypt streaming SHA-256 computation (0.0 to 1.0) */
    data class Hashing(val progress: Float) : UploadStage

    /** Local sub-sampled preview generation and WebP encoding */
    data object GeneratingThumbnail : UploadStage

    /** Hardware-accelerated AES-256-GCM authenticated payload encryption */
    data object Encrypting : UploadStage

    /** Streaming encrypted bytes to server CAS socket with real-time byte metrics */
    data class Uploading(
        val progress: Float,
        val bytesSent: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long = 0L
    ) : UploadStage

    /** Atomic SQLite persistence and CRDT action queueing */
    data object Finalizing : UploadStage

    /** Upload and persistence completed */
    data object Completed : UploadStage

    /** Upload aborted due to error */
    data class Failed(val error: String) : UploadStage
}
