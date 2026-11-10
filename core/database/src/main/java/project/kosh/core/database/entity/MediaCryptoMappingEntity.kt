package project.kosh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import project.kosh.core.model.MediaCryptoMapping
import project.kosh.core.model.UploadStatus

/**
 * SQLite Room entity storing cryptographic keys and server Content-Addressable Storage (CAS) mapping.
 *
 * Implements Kosh's Zero-Knowledge architecture:
 * - The original unencrypted file hash is tracked ([plaintextSha256]) for local duplicate detection.
 * - The encrypted CAS blob hash ([ciphertextHash]) is populated once the server commits the payload.
 * - The asset's Data Encryption Key (DEK) is strictly wrapped under the Master Vault Key
 *   as a 60-byte (120 hex character) AEAD envelope ([wrappedDek]).
 * - Deletion of the parent [MediaAssetEntity] cascades immediately to wipe associated keys.
 * - Replicated via Delta-CRDT with [lamportClock], [deviceId], and [isDeleted] tombstones.
 */
@Entity(
    tableName = "media_crypto_mapping",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["file_id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["plaintext_sha256"]),
        Index(value = ["ciphertext_hash"]),
        Index(value = ["upload_status"])
    ]
)
data class MediaCryptoMappingEntity(
    @PrimaryKey
    @ColumnInfo(name = "file_id")
    val fileId: String,

    @ColumnInfo(name = "plaintext_sha256")
    val plaintextSha256: String,

    @ColumnInfo(name = "ciphertext_hash")
    val ciphertextHash: String? = null,

    @ColumnInfo(name = "thumbnail_ciphertext_hash")
    val thumbnailCiphertextHash: String? = null,

    @ColumnInfo(name = "wrapped_dek")
    val wrappedDek: String,

    @ColumnInfo(name = "wrapped_thumbnail_dek")
    val wrappedThumbnailDek: String? = null,

    @ColumnInfo(name = "upload_status")
    val uploadStatus: UploadStatus = UploadStatus.PENDING,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "lamport_clock", defaultValue = "0")
    val lamportClock: Long = 0L,

    @ColumnInfo(name = "device_id", defaultValue = "''")
    val deviceId: String = "",

    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "thumbnail_file_id")
    val thumbnailFileId: String? = null
) {
    /**
     * Backwards-compatible alias for legacy calls referencing plaintextHash.
     */
    val plaintextHash: String get() = plaintextSha256

    /**
     * Converts this database entity into the clean domain [MediaCryptoMapping] model.
     */
    fun toDomainModel(): MediaCryptoMapping = MediaCryptoMapping(
        fileId = fileId,
        plaintextSha256 = plaintextSha256,
        ciphertextHash = ciphertextHash,
        thumbnailCiphertextHash = thumbnailCiphertextHash,
        wrappedDek = wrappedDek,
        wrappedThumbnailDek = wrappedThumbnailDek,
        uploadStatus = uploadStatus,
        lastSyncedAt = lastSyncedAt,
        errorMessage = errorMessage,
        lamportClock = lamportClock,
        deviceId = deviceId,
        isDeleted = isDeleted,
        thumbnailFileId = thumbnailFileId
    )

    companion object {
        /**
         * Maps a domain [MediaCryptoMapping] to a persistable [MediaCryptoMappingEntity].
         */
        fun fromDomainModel(domain: MediaCryptoMapping): MediaCryptoMappingEntity =
            MediaCryptoMappingEntity(
                fileId = domain.fileId,
                plaintextSha256 = domain.plaintextSha256,
                ciphertextHash = domain.ciphertextHash,
                thumbnailCiphertextHash = domain.thumbnailCiphertextHash,
                wrappedDek = domain.wrappedDek,
                wrappedThumbnailDek = domain.wrappedThumbnailDek,
                uploadStatus = domain.uploadStatus,
                lastSyncedAt = domain.lastSyncedAt,
                errorMessage = domain.errorMessage,
                lamportClock = domain.lamportClock,
                deviceId = domain.deviceId,
                isDeleted = domain.isDeleted,
                thumbnailFileId = domain.thumbnailFileId
            )
    }
}
