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
 * - The original unencrypted file hash is tracked ([plaintextHash]) for local duplicate detection.
 * - The encrypted CAS blob hash ([ciphertextHash]) is populated once the server commits the payload.
 * - The asset's Data Encryption Key (DEK) is strictly wrapped under the Master Vault Key
 *   as a 60-byte (120 hex character) AEAD envelope ([wrappedDek]).
 * - Deletion of the parent [MediaAssetEntity] cascades immediately to wipe associated keys.
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
        Index(value = ["plaintext_hash"]),
        Index(value = ["ciphertext_hash"]),
        Index(value = ["upload_status"])
    ]
)
data class MediaCryptoMappingEntity(
    @PrimaryKey
    @ColumnInfo(name = "file_id")
    val fileId: String,

    @ColumnInfo(name = "plaintext_hash")
    val plaintextHash: String,

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
    val errorMessage: String? = null
) {
    /**
     * Converts this database entity into the clean domain [MediaCryptoMapping] model.
     */
    fun toDomainModel(): MediaCryptoMapping = MediaCryptoMapping(
        fileId = fileId,
        plaintextHash = plaintextHash,
        ciphertextHash = ciphertextHash,
        thumbnailCiphertextHash = thumbnailCiphertextHash,
        wrappedDek = wrappedDek,
        wrappedThumbnailDek = wrappedThumbnailDek,
        uploadStatus = uploadStatus,
        lastSyncedAt = lastSyncedAt,
        errorMessage = errorMessage
    )

    companion object {
        /**
         * Maps a domain [MediaCryptoMapping] to a persistable [MediaCryptoMappingEntity].
         */
        fun fromDomainModel(domain: MediaCryptoMapping): MediaCryptoMappingEntity =
            MediaCryptoMappingEntity(
                fileId = domain.fileId,
                plaintextHash = domain.plaintextHash,
                ciphertextHash = domain.ciphertextHash,
                thumbnailCiphertextHash = domain.thumbnailCiphertextHash,
                wrappedDek = domain.wrappedDek,
                wrappedThumbnailDek = domain.wrappedThumbnailDek,
                uploadStatus = domain.uploadStatus,
                lastSyncedAt = domain.lastSyncedAt,
                errorMessage = domain.errorMessage
            )
    }
}
