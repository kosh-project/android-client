package project.kosh.core.model

import kotlinx.serialization.Serializable

/**
 * Primary domain representation of an uploaded or locally indexed media asset (photo or video).
 *
 * @property fileId Unique UUID identifier serving as primary key.
 * @property thumbnailHash BLAKE3 hash / identifier of downscaled preview blob stored on server.
 * @property mediaType MIME type (e.g. "image/jpeg", "image/png", "video/mp4").
 * @property width Pixel width of the media item.
 * @property height Pixel height of the media item.
 * @property durationMs Video duration in milliseconds, or null for static images.
 * @property fileSize File size in raw binary bytes.
 * @property exif Camera and device EXIF properties including geolocation.
 * @property capturedAt Unix epoch millisecond timestamp from EXIF header.
 * @property createdAt Epoch timestamp when asset record was created locally.
 * @property updatedAt Epoch timestamp when asset record was last modified.
 * @property assetTag Storage domain tag in CAS vault (default: GALLERY_ITEM).
 * @property lamportClock Logical clock value for CRDT ordering.
 * @property deviceId Originating device ID.
 * @property isDeleted Tombstone flag for distributed deletion.
 */
@Serializable
data class MediaAsset(
    val fileId: String,
    val thumbnailHash: String? = null,
    val mediaType: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val fileSize: Long,
    val exif: ExifMetadata = ExifMetadata(),
    val capturedAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val assetTag: AssetTag = AssetTag.GALLERY_ITEM,
    val lamportClock: Long = 0L,
    val deviceId: String = "",
    val isDeleted: Boolean = false
)
