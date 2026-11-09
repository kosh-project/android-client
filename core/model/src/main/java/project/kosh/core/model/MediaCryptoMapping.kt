package project.kosh.core.model

import kotlinx.serialization.Serializable

/**
 * Cryptographic envelope mapping and Content-Addressable Storage (CAS) upload state.
 *
 * Maps a [MediaAsset] to its pre-upload plaintext hash, server ciphertext hash,
 * and ChaCha20-Poly1305 / AES-256-GCM wrapped Data Encryption Key (DEK).
 *
 * @property fileId Foreign key referencing [MediaAsset.fileId].
 * @property plaintextHash BLAKE3 / SHA-256 digest of uncompressed plaintext file prior to encryption.
 * @property ciphertextHash BLAKE3 digest calculated by server after receiving encrypted payload.
 * @property thumbnailCiphertextHash BLAKE3 digest of the encrypted downscaled preview blob.
 * @property wrappedDek 60-byte (120 hex chars) envelope encrypting per-file DEK under Master Vault Key.
 * @property wrappedThumbnailDek Optional wrapped DEK for thumbnail preview, or null if shared with DEK.
 * @property uploadStatus Lifecycle state of asset synchronization with server CAS vault.
 * @property lastSyncedAt Epoch timestamp when server acknowledged upload completion.
 * @property errorMessage Descriptive error log if synchronization failed.
 */
@Serializable
data class MediaCryptoMapping(
    val fileId: String,
    val plaintextHash: String,
    val ciphertextHash: String? = null,
    val thumbnailCiphertextHash: String? = null,
    val wrappedDek: String,
    val wrappedThumbnailDek: String? = null,
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
    val lastSyncedAt: Long? = null,
    val errorMessage: String? = null
)
