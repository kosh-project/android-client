package project.kosh.feature.gallery.model

import android.net.Uri

/**
 * Domain representation of an on-device media folder / album discovered via MediaStore.
 *
 * @property bucketId ContentProvider MediaStore bucket identifier.
 * @property displayName Human-readable folder name (e.g. "Camera", "Screenshots", "WhatsApp Images").
 * @property mediaCount Total number of photos and videos indexed inside this bucket.
 * @property representativeUri Thumbnail URI representing the latest media item in the bucket.
 * @property previewUris Up to 3 latest media URIs used for stacked album card previews.
 * @property isSelectedForSync Whether this bucket is enrolled in automatic private node backup.
 */
data class MediaFolder(
    val bucketId: String,
    val displayName: String,
    val mediaCount: Int,
    val representativeUri: Uri?,
    val previewUris: List<Uri> = emptyList(),
    val isSelectedForSync: Boolean = false
)

/**
 * Local media item discovered on device ready for zero-knowledge vault ingestion.
 *
 * @property id MediaStore content ID.
 * @property uri Content URI (e.g. `content://media/external/images/media/1234`).
 * @property displayName File name (e.g. `IMG_20261110_1345.jpg`).
 * @property mimeType Media MIME type (e.g. `image/jpeg`).
 * @property size File size in bytes.
 * @property dateModified Epoch timestamp of last modification.
 * @property bucketId Identifier of the parent media folder.
 */
data class DiscoveredMediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val dateModified: Long,
    val bucketId: String
)
