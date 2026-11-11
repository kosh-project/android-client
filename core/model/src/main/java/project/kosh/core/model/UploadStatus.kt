package project.kosh.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class UploadStatus {
    PENDING,
    UPLOADING,
    PAUSED,
    SYNCED,
    FAILED
}