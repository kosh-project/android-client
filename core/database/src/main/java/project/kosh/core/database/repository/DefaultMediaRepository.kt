package project.kosh.core.database.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import project.kosh.core.database.KoshDatabase
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.dao.MediaCryptoDao
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.model.MediaAsset
import project.kosh.core.model.MediaCryptoMapping
import project.kosh.core.model.UploadStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [MediaRepository] backed by AndroidX Room DAOs.
 */
@Singleton
class DefaultMediaRepository @Inject constructor(
    private val database: KoshDatabase,
    private val mediaAssetDao: MediaAssetDao,
    private val mediaCryptoDao: MediaCryptoDao
) : MediaRepository {

    override fun observeAllMedia(): Flow<List<MediaAsset>> =
        mediaAssetDao.observeAllAssets().map { list -> list.map { it.toDomainModel() } }

    override fun observeMediaById(fileId: String): Flow<MediaAsset?> =
        mediaAssetDao.observeAssetById(fileId).map { it?.toDomainModel() }

    override suspend fun getMediaById(fileId: String): MediaAsset? =
        mediaAssetDao.getAssetById(fileId)?.toDomainModel()

    override suspend fun getMediaCount(): Int =
        mediaAssetDao.getAssetCount()

    override fun observeMediaCount(): Flow<Int> =
        mediaAssetDao.observeAssetCount()

    override suspend fun getMediaPaged(limit: Int, offset: Int): List<MediaAsset> =
        mediaAssetDao.getAssetsPaged(limit, offset).map { it.toDomainModel() }

    override suspend fun getMediaCapturedBefore(beforeCapturedAt: Long, limit: Int): List<MediaAsset> =
        mediaAssetDao.getAssetsCapturedBefore(beforeCapturedAt, limit).map { it.toDomainModel() }

    override suspend fun getMediaBeforeCursor(
        beforeCapturedAt: Long?,
        beforeFileId: String?,
        limit: Int
    ): List<MediaAsset> =
        mediaAssetDao.getAssetsBeforeCursor(beforeCapturedAt, beforeFileId, limit).map { it.toDomainModel() }

    override fun observeMediaByType(typePrefix: String): Flow<List<MediaAsset>> =
        mediaAssetDao.observeAssetsByType(typePrefix).map { list -> list.map { it.toDomainModel() } }

    override suspend fun getMediaByType(typePrefix: String): List<MediaAsset> =
        mediaAssetDao.getAssetsByType(typePrefix).map { it.toDomainModel() }

    override suspend fun getMediaCapturedBetween(startTime: Long, endTime: Long): List<MediaAsset> =
        mediaAssetDao.getAssetsCapturedBetween(startTime, endTime).map { it.toDomainModel() }

    override fun observeMediaCapturedBetween(startTime: Long, endTime: Long): Flow<List<MediaAsset>> =
        mediaAssetDao.observeAssetsCapturedBetween(startTime, endTime).map { list -> list.map { it.toDomainModel() } }

    override suspend fun saveMediaWithCrypto(asset: MediaAsset, crypto: MediaCryptoMapping) {
        database.withTransaction {
            mediaAssetDao.upsert(MediaAssetEntity.fromDomainModel(asset))
            mediaCryptoDao.upsert(MediaCryptoMappingEntity.fromDomainModel(crypto))
        }
    }

    override suspend fun saveMediaBatch(assets: List<MediaAsset>, cryptos: List<MediaCryptoMapping>) {
        require(assets.size == cryptos.size) {
            "Assets count (${assets.size}) must match crypto mappings count (${cryptos.size})"
        }
        database.withTransaction {
            mediaAssetDao.upsertAll(assets.map { MediaAssetEntity.fromDomainModel(it) })
            mediaCryptoDao.upsertAll(cryptos.map { MediaCryptoMappingEntity.fromDomainModel(it) })
        }
    }

    override suspend fun deleteMedia(fileId: String): Boolean =
        mediaAssetDao.deleteById(fileId) > 0

    override suspend fun deleteMediaList(fileIds: List<String>): Int =
        mediaAssetDao.deleteByIds(fileIds)

    override suspend fun clearAllMedia(): Int =
        database.withTransaction {
            mediaCryptoDao.clearAll()
            mediaAssetDao.clearAll()
        }

    override suspend fun getCryptoMapping(fileId: String): MediaCryptoMapping? =
        mediaCryptoDao.getByFileId(fileId)?.toDomainModel()

    override fun observeCryptoMapping(fileId: String): Flow<MediaCryptoMapping?> =
        mediaCryptoDao.observeByFileId(fileId).map { it?.toDomainModel() }

    override suspend fun getMediaWithCrypto(fileId: String): MediaWithCryptoDetails? =
        mediaAssetDao.getMediaWithCryptoById(fileId)

    override fun observeMediaWithCrypto(fileId: String): Flow<MediaWithCryptoDetails?> =
        mediaAssetDao.observeMediaWithCryptoById(fileId)

    override fun observeAllMediaWithCrypto(): Flow<List<MediaWithCryptoDetails>> =
        mediaAssetDao.observeAllMediaWithCrypto()

    override suspend fun findByPlaintextHash(plaintextHash: String): MediaCryptoMapping? =
        mediaCryptoDao.getByPlaintextHash(plaintextHash)?.toDomainModel()

    override suspend fun findByCiphertextHash(ciphertextHash: String): MediaCryptoMapping? =
        mediaCryptoDao.getByCiphertextHash(ciphertextHash)?.toDomainModel()

    override suspend fun getPendingUploads(): List<MediaCryptoMapping> =
        mediaCryptoDao.getPendingOrFailedUploads().map { it.toDomainModel() }

    override fun observePendingUploads(): Flow<List<MediaCryptoMapping>> =
        mediaCryptoDao.observePendingOrFailedUploads().map { list -> list.map { it.toDomainModel() } }

    override suspend fun getUnsyncedUploads(): List<MediaCryptoMapping> =
        mediaCryptoDao.getUnsyncedUploads().map { it.toDomainModel() }

    override fun observeUnsyncedUploads(): Flow<List<MediaCryptoMapping>> =
        mediaCryptoDao.observeUnsyncedUploads().map { list -> list.map { it.toDomainModel() } }

    override suspend fun resetStuckUploads(): Int =
        mediaCryptoDao.resetStuckUploads()

    override suspend fun updateUploadStatus(
        fileId: String,
        status: UploadStatus,
        ciphertextHash: String?,
        thumbnailCiphertextHash: String?,
        lastSyncedAt: Long?,
        errorMessage: String?
    ): Boolean =
        mediaCryptoDao.updateUploadStatus(
            fileId = fileId,
            status = status,
            ciphertextHash = ciphertextHash,
            thumbnailCiphertextHash = thumbnailCiphertextHash,
            lastSyncedAt = lastSyncedAt,
            errorMessage = errorMessage
        ) > 0
}
