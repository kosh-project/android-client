package project.kosh.core.model

import kotlinx.serialization.Serializable

/**
 * Domain representation of a Google Photos-style media collection.
 *
 * @property albumId Unique UUID identifier for the album.
 * @property title User-facing album title.
 * @property description Optional album description or subtitle.
 * @property coverFileId Optional foreign key referencing cover [MediaAsset.fileId].
 * @property createdAt Epoch timestamp when the album was created.
 * @property updatedAt Epoch timestamp when the album was last updated.
 * @property lamportClock Logical clock value for CRDT ordering.
 * @property deviceId Originating device ID.
 * @property isDeleted Tombstone flag for distributed deletion.
 */
@Serializable
data class Album(
    val albumId: String,
    val title: String,
    val description: String? = null,
    val coverFileId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lamportClock: Long = 0L,
    val deviceId: String = "",
    val isDeleted: Boolean = false
)
