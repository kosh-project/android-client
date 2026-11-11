package project.kosh.core.database.crdt

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import project.kosh.core.crypto.AesGcmEnvelope
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.database.KoshDatabase
import project.kosh.core.database.dao.AlbumDao
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.dao.MediaCryptoDao
import project.kosh.core.database.dao.SyncWatermarkDao
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.entity.AlbumMediaCrossRef
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.database.entity.SyncWatermarkEntity
import project.kosh.core.model.AssetTag
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.SyncNamespace
import project.kosh.core.model.snapshot.GallerySnapshotBundle
import project.kosh.core.model.snapshot.SnapshotAlbum
import project.kosh.core.model.snapshot.SnapshotAlbumCrossRef
import project.kosh.core.model.snapshot.SnapshotMediaAsset
import project.kosh.core.model.snapshot.SnapshotMediaCrypto
import project.kosh.core.network.AssetClient
import project.kosh.core.network.SyncClient
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages creation, encryption, CAS blob upload, server registration, and bootstrap restoration
 * of encrypted gallery database snapshots.
 */
@OptIn(ExperimentalSerializationApi::class)
@Singleton
class SnapshotSyncManager @Inject constructor(
    private val syncClient: SyncClient,
    private val assetClient: AssetClient,
    private val mediaAssetDao: MediaAssetDao,
    private val mediaCryptoDao: MediaCryptoDao,
    private val albumDao: AlbumDao,
    private val syncWatermarkDao: SyncWatermarkDao,
    private val database: KoshDatabase,
    @ApplicationContext private val context: Context
) {
    private val protoBuf = ProtoBuf { }

    /**
     * Serializes all non-deleted local Room records into an encrypted Protobuf bundle,
     * uploads the bundle to CAS, and registers the snapshot with the sync committer.
     *
     * @param masterKey Master Vault Key used for AES-256-GCM envelope encryption.
     * @param namespace Domain sync namespace (default: GALLERY).
     * @return [NetworkResult.Success] containing the registered snapshot row ID, or 0 if nothing to snapshot.
     */
    suspend fun createAndUploadSnapshot(
        masterKey: MasterVaultKey,
        namespace: SyncNamespace = SyncNamespace.GALLERY
    ): NetworkResult<Long> {
        if (masterKey.isClosed) {
            return NetworkResult.fromThrowable(IllegalStateException("MasterVaultKey is closed"))
        }

        return try {
            val assets = mediaAssetDao.getAllAssets()
            val cryptoMappings = mediaCryptoDao.getAll()
            val albums = albumDao.getAllAlbums()
            val crossRefs = albumDao.getAllCrossRefs()

            val watermark = syncWatermarkDao.getWatermark(namespace.value)
            val seq = watermark?.lastSequenceId ?: 0L

            // If no rows exist or watermark is 0 with no assets, return early
            if ((assets.isEmpty() && albums.isEmpty()) || (seq == 0L && assets.isEmpty())) {
                return NetworkResult.Success(0L)
            }

            val assetIds = assets.map { it.fileId }.toSet()
            val validCryptoMappings = cryptoMappings.filter { it.fileId in assetIds }
            val albumIds = albums.map { it.albumId }.toSet()
            val validAlbums = albums.map { album ->
                if (album.coverFileId != null && album.coverFileId !in assetIds) {
                    album.copy(coverFileId = null)
                } else {
                    album
                }
            }
            val validCrossRefs = crossRefs.filter { it.albumId in albumIds && it.fileId in assetIds }

            val bundle = GallerySnapshotBundle(
                version = 1,
                generatedAtEpochMs = System.currentTimeMillis(),
                watermarkSeq = seq,
                assets = assets.map { it.toSnapshot() },
                cryptoMappings = validCryptoMappings.map { it.toSnapshot() },
                albums = validAlbums.map { it.toSnapshot() },
                albumCrossRefs = validCrossRefs.map { it.toSnapshot() }
            )

            val payload = protoBuf.encodeToByteArray(GallerySnapshotBundle.serializer(), bundle)
            val encryptedBytes = AesGcmEnvelope.encrypt(payload, masterKey.keyBytes)

            val uploadResult = assetClient.uploadBytes(
                bytes = encryptedBytes,
                tag = AssetTag.GALLERY_META,
                originalFileName = "snapshot_${namespace.value}_$seq.bin"
            )

            val blobHash = when (uploadResult) {
                is NetworkResult.Success -> uploadResult.data
                is NetworkResult.Failure -> return uploadResult
            }

            syncClient.registerSnapshot(namespace, seq, blobHash)
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Checks server for the latest snapshot, downloads and decrypts it, and bulk-inserts
     * materialized state into local SQLite tables within an atomic transaction.
     *
     * @param masterKey Master Vault Key used for AES-256-GCM envelope decryption.
     * @param namespace Domain sync namespace (default: GALLERY).
     * @return [NetworkResult.Success] containing true if state was restored, or false if already up-to-date or absent.
     */
    suspend fun restoreFromLatestSnapshot(
        masterKey: MasterVaultKey,
        namespace: SyncNamespace = SyncNamespace.GALLERY
    ): NetworkResult<Boolean> {
        if (masterKey.isClosed) {
            return NetworkResult.fromThrowable(IllegalStateException("MasterVaultKey is closed"))
        }

        val latestSnapshotResult = syncClient.getLatestSnapshot(namespace)
        val snapshot = when (latestSnapshotResult) {
            is NetworkResult.Success -> latestSnapshotResult.data
            is NetworkResult.Failure -> return latestSnapshotResult
        }

        if (snapshot == null) {
            return NetworkResult.Success(false)
        }

        val watermark = syncWatermarkDao.getWatermark(namespace.value)
        val localWatermarkSeq = watermark?.lastSequenceId ?: 0L
        val localAssetCount = mediaAssetDao.getCount()

        // Compare snapshot.snapshotSeq with local watermark. If local watermark >= snapshotSeq and local assets exist, return Success(false)
        if (localWatermarkSeq >= snapshot.snapshotSeq && localAssetCount > 0) {
            return NetworkResult.Success(false)
        }

        val downloadResult = assetClient.downloadBlob(snapshot.blobHash)
        val encryptedBytes = when (downloadResult) {
            is NetworkResult.Success -> downloadResult.data
            is NetworkResult.Failure -> return downloadResult
        }

        val decryptedBytes = try {
            AesGcmEnvelope.decrypt(encryptedBytes, masterKey.keyBytes)
        } catch (e: Exception) {
            return NetworkResult.fromThrowable(e)
        }

        val bundle = try {
            protoBuf.decodeFromByteArray(GallerySnapshotBundle.serializer(), decryptedBytes)
        } catch (e: Exception) {
            return NetworkResult.fromThrowable(e)
        }

        return try {
            database.withTransaction {
                val assetIds = bundle.assets.map { it.fileId }.toSet()
                if (bundle.assets.isNotEmpty()) {
                    mediaAssetDao.insertAll(bundle.assets.map { it.toEntity() })
                }
                val validCrypto = bundle.cryptoMappings
                    .filter { it.fileId in assetIds }
                    .map { it.toEntity() }
                if (validCrypto.isNotEmpty()) {
                    mediaCryptoDao.insertAll(validCrypto)
                }
                val validAlbums = bundle.albums.map { album ->
                    if (album.coverFileId != null && album.coverFileId !in assetIds) {
                        album.copy(coverFileId = null).toEntity()
                    } else {
                        album.toEntity()
                    }
                }
                if (validAlbums.isNotEmpty()) {
                    albumDao.insertAllAlbums(validAlbums)
                }
                val albumIds = bundle.albums.map { it.albumId }.toSet()
                val validCrossRefs = bundle.albumCrossRefs
                    .filter { it.albumId in albumIds && it.fileId in assetIds }
                    .map { it.toEntity() }
                if (validCrossRefs.isNotEmpty()) {
                    albumDao.insertAllCrossRefs(validCrossRefs)
                }

                syncWatermarkDao.upsertWatermark(
                    SyncWatermarkEntity(
                        namespace = namespace.value,
                        lastFile = watermark?.lastFile ?: SyncClient.DEFAULT_SEGMENT_FILE,
                        lastOffset = watermark?.lastOffset ?: 0L,
                        lastSequenceId = snapshot.snapshotSeq,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            }
            NetworkResult.Success(true)
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Determines whether the given [baseUrl] refers to a local network host (RFC 1918 private IP,
     * loopback 127.0.0.1 / localhost, or .local mDNS hostname).
     */
    fun isLocalNetworkEndpoint(baseUrl: String): Boolean {
        val host = try {
            val normalized = if (!baseUrl.contains("://")) "http://$baseUrl" else baseUrl
            val uri = URI(normalized)
            uri.host ?: normalized.substringAfter("://").substringBefore("/").substringBeforeLast(":")
        } catch (_: Exception) {
            baseUrl.substringAfter("://").substringBefore("/").substringBeforeLast(":")
        }.trim().lowercase().removeSurrounding("[", "]")

        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.removeSuffix(".").endsWith(".local")) {
            return true
        }

        val parts = host.split(".")
        if (parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { char -> char.isDigit() } }) {
            val octets = parts.mapNotNull { it.toIntOrNull() }
            if (octets.size == 4 && octets.all { it in 0..255 }) {
                // 10.0.0.0/8
                if (octets[0] == 10) return true
                // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
                if (octets[0] == 172 && octets[1] in 16..31) return true
                // 192.168.0.0/16
                if (octets[0] == 192 && octets[1] == 168) return true
                // 127.0.0.0/8
                if (octets[0] == 127) return true
            }
        }
        return false
    }
}

private fun MediaAssetEntity.toSnapshot(): SnapshotMediaAsset = SnapshotMediaAsset(
    fileId = fileId,
    thumbnailHash = thumbnailHash,
    mediaType = mediaType,
    width = width,
    height = height,
    durationMs = durationMs,
    fileSize = fileSize,
    deviceMake = deviceMake,
    deviceModel = deviceModel,
    lensModel = lensModel,
    focalLength = focalLength,
    aperture = aperture,
    iso = iso,
    exposureTime = exposureTime,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    capturedAt = capturedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    assetTag = assetTag,
    lamportClock = lamportClock,
    deviceId = deviceId,
    isDeleted = isDeleted
)

private fun SnapshotMediaAsset.toEntity(): MediaAssetEntity = MediaAssetEntity(
    fileId = fileId,
    thumbnailHash = thumbnailHash,
    mediaType = mediaType,
    width = width,
    height = height,
    durationMs = durationMs,
    fileSize = fileSize,
    deviceMake = deviceMake,
    deviceModel = deviceModel,
    lensModel = lensModel,
    focalLength = focalLength,
    aperture = aperture,
    iso = iso,
    exposureTime = exposureTime,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    capturedAt = capturedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    assetTag = assetTag,
    lamportClock = lamportClock,
    deviceId = deviceId,
    isDeleted = isDeleted
)

private fun MediaCryptoMappingEntity.toSnapshot(): SnapshotMediaCrypto = SnapshotMediaCrypto(
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

private fun SnapshotMediaCrypto.toEntity(): MediaCryptoMappingEntity = MediaCryptoMappingEntity(
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

private fun AlbumEntity.toSnapshot(): SnapshotAlbum = SnapshotAlbum(
    albumId = albumId,
    title = title,
    description = description,
    coverFileId = coverFileId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lamportClock = lamportClock,
    deviceId = deviceId,
    isDeleted = isDeleted
)

private fun SnapshotAlbum.toEntity(): AlbumEntity = AlbumEntity(
    albumId = albumId,
    title = title,
    description = description,
    coverFileId = coverFileId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lamportClock = lamportClock,
    deviceId = deviceId,
    isDeleted = isDeleted
)

private fun AlbumMediaCrossRef.toSnapshot(): SnapshotAlbumCrossRef = SnapshotAlbumCrossRef(
    albumId = albumId,
    fileId = fileId,
    addedAt = addedAt,
    positionIndex = positionIndex,
    lamportClock = lamportClock,
    deviceId = deviceId,
    isDeleted = isDeleted
)

private fun SnapshotAlbumCrossRef.toEntity(): AlbumMediaCrossRef = AlbumMediaCrossRef(
    albumId = albumId,
    fileId = fileId,
    addedAt = addedAt,
    positionIndex = positionIndex,
    lamportClock = lamportClock,
    deviceId = deviceId,
    isDeleted = isDeleted
)
