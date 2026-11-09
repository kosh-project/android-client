package project.kosh.core.database.repository

import kotlinx.coroutines.flow.Flow
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.model.MediaAsset
import project.kosh.core.model.MediaCryptoMapping
import project.kosh.core.model.UploadStatus

/**
 * Domain repository managing local media asset cataloging, EXIF metadata, and cryptographic vault mapping.
 */
interface MediaRepository {

    fun observeAllMedia(): Flow<List<MediaAsset>>

    fun observeMediaById(fileId: String): Flow<MediaAsset?>

    suspend fun getMediaById(fileId: String): MediaAsset?

    suspend fun getMediaCount(): Int

    fun observeMediaCount(): Flow<Int>

    suspend fun getMediaPaged(limit: Int, offset: Int): List<MediaAsset>

    suspend fun getMediaCapturedBefore(beforeCapturedAt: Long, limit: Int): List<MediaAsset>

    suspend fun getMediaBeforeCursor(beforeCapturedAt: Long? = null, beforeFileId: String? = null, limit: Int = 50): List<MediaAsset>

    fun observeMediaByType(typePrefix: String): Flow<List<MediaAsset>>

    suspend fun getMediaByType(typePrefix: String): List<MediaAsset>

    suspend fun getMediaCapturedBetween(startTime: Long, endTime: Long): List<MediaAsset>

    fun observeMediaCapturedBetween(startTime: Long, endTime: Long): Flow<List<MediaAsset>>

    suspend fun saveMediaWithCrypto(asset: MediaAsset, crypto: MediaCryptoMapping)

    suspend fun saveMediaBatch(assets: List<MediaAsset>, cryptos: List<MediaCryptoMapping>)

    suspend fun deleteMedia(fileId: String): Boolean

    suspend fun deleteMediaList(fileIds: List<String>): Int

    suspend fun clearAllMedia(): Int

    suspend fun getCryptoMapping(fileId: String): MediaCryptoMapping?

    fun observeCryptoMapping(fileId: String): Flow<MediaCryptoMapping?>

    suspend fun getMediaWithCrypto(fileId: String): MediaWithCryptoDetails?

    fun observeMediaWithCrypto(fileId: String): Flow<MediaWithCryptoDetails?>

    fun observeAllMediaWithCrypto(): Flow<List<MediaWithCryptoDetails>>

    suspend fun findByPlaintextHash(plaintextHash: String): MediaCryptoMapping?

    suspend fun findByCiphertextHash(ciphertextHash: String): MediaCryptoMapping?

    suspend fun getPendingUploads(): List<MediaCryptoMapping>

    fun observePendingUploads(): Flow<List<MediaCryptoMapping>>

    suspend fun getUnsyncedUploads(): List<MediaCryptoMapping>

    fun observeUnsyncedUploads(): Flow<List<MediaCryptoMapping>>

    suspend fun resetStuckUploads(): Int

    suspend fun updateUploadStatus(
        fileId: String,
        status: UploadStatus,
        ciphertextHash: String? = null,
        thumbnailCiphertextHash: String? = null,
        lastSyncedAt: Long? = null,
        errorMessage: String? = null
    ): Boolean
}
