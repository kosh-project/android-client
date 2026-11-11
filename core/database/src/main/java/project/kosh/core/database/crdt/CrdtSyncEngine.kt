package project.kosh.core.database.crdt

import androidx.room.withTransaction
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import project.kosh.core.crypto.LamportClockManager
import project.kosh.core.crypto.LedgerCrypto
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.database.KoshDatabase
import project.kosh.core.database.dao.AlbumDao
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.dao.MediaCryptoDao
import project.kosh.core.database.dao.SyncQueueDao
import project.kosh.core.database.dao.SyncWatermarkDao
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.entity.AlbumMediaCrossRef
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.database.entity.SyncQueueEntity
import project.kosh.core.database.entity.SyncWatermarkEntity
import project.kosh.core.model.AssetTag
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.SyncNamespace
import project.kosh.core.model.UploadStatus
import project.kosh.core.model.crdt.CrdtAction
import project.kosh.core.model.crdt.SyncDeltaBundle
import project.kosh.core.network.SyncClient
import project.kosh.core.network.SyncHttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result metrics for a completed sync cycle.
 */
data class SyncResult(
    val pulledFramesCount: Int,
    val pulledActionsCount: Int,
    val reconciledQueueCount: Int,
    val pushedBundlesCount: Int,
    val pushedActionsCount: Int
)

/**
 * Core Delta-CRDT synchronization engine for Kosh.
 *
 * Implements:
 * 1. Pull-Before-Push: queries inbound ledger updates, decrypts wire frames with $K_{ledger}$,
 *    and applies deterministic Last-Write-Wins (LWW) conflict resolution using Lamport logical clocks
 *    and device IDs.
 * 2. Queue Reconciliation: purges obsolete offline actions invalidated by peer mutations,
 *    and advances Lamport clocks of remaining pending actions.
 * 3. Sliced Outbound Push: batches remaining offline actions into <= 512 KiB bundles,
 *    encrypts with $K_{ledger}$, and uploads to `POST /api/v1/sync/delta`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Singleton
class CrdtSyncEngine @Inject constructor(
    private val syncClient: SyncClient,
    private val mediaAssetDao: MediaAssetDao,
    private val mediaCryptoDao: MediaCryptoDao,
    private val albumDao: AlbumDao,
    private val syncQueueDao: SyncQueueDao,
    private val syncWatermarkDao: SyncWatermarkDao,
    private val lamportClockManager: LamportClockManager,
    private val database: KoshDatabase
) {
    private val protoBuf = ProtoBuf { }

    companion object {
        const val MAX_SLICE_SIZE = 512 * 1024 // 512 KiB batch ceiling
        const val SEGMENT_HEADER_SIZE = 500L
    }

    suspend fun getPendingQueueCount(namespace: String = "gallery"): Int =
        syncQueueDao.getPendingCount(namespace)

    /**
     * Executes a complete bidirectional sync cycle for the specified [namespace].
     *
     * @param masterVaultKey Master encryption key used to derive $K_{ledger}$.
     * @param namespace Ledger domain namespace (default: GALLERY).
     * @return [SyncResult] metrics of the completed synchronization cycle.
     */
    suspend fun sync(
        masterVaultKey: MasterVaultKey,
        namespace: SyncNamespace = SyncNamespace.GALLERY
    ): SyncResult {
        val ledgerKey = LedgerCrypto.deriveLedgerKey(masterVaultKey)

        // Phase 1: Inbound Pull (Pull-Before-Push)
        val pullMetrics = pullInboundDeltas(namespace, ledgerKey)

        // Phase 2: Local Queue Reconciliation
        val reconciledCount = reconcileQueue(namespace.value)

        // Phase 3: Sliced Outbound Push
        val pushMetrics = pushOutboundSlices(namespace, ledgerKey)

        return SyncResult(
            pulledFramesCount = pullMetrics.first,
            pulledActionsCount = pullMetrics.second,
            reconciledQueueCount = reconciledCount,
            pushedBundlesCount = pushMetrics.first,
            pushedActionsCount = pushMetrics.second
        )
    }

    /**
     * Pulls and materializes all inbound deltas since the device's last read watermark.
     */
    suspend fun pullInboundDeltas(
        namespace: SyncNamespace,
        ledgerKey: ByteArray
    ): Pair<Int, Int> {
        val watermark = syncWatermarkDao.getWatermark(namespace.value)
        val startFile = watermark?.lastFile ?: SyncClient.DEFAULT_SEGMENT_FILE
        val startOffset = watermark?.lastOffset ?: 0L

        var currentFile = startFile
        var currentOffset = maxOf(startOffset, SEGMENT_HEADER_SIZE)
        var lastSeq = watermark?.lastSequenceId ?: 0L
        var pulledFrames = 0
        var pulledActions = 0

        if (watermark == null) {
            syncWatermarkDao.upsertWatermark(
                SyncWatermarkEntity(
                    namespace = namespace.value,
                    lastFile = currentFile,
                    lastOffset = currentOffset,
                    lastSequenceId = lastSeq
                )
            )
        }

        try {
            syncClient.streamDeltas(namespace, fileName = currentFile, offset = currentOffset).collect { frame ->
                pulledFrames++
                lastSeq = frame.sequenceId

                currentOffset += 12L + frame.length.toLong()

                val decryptedPayload = LedgerCrypto.decryptBundle(frame.payload, ledgerKey)
                val bundle = protoBuf.decodeFromByteArray(SyncDeltaBundle.serializer(), decryptedPayload)

                database.withTransaction {
                    for (action in bundle.actions) {
                        pulledActions++
                        applyRemoteAction(action)
                        lamportClockManager.update(action.lamportClock)
                    }
                }

                syncWatermarkDao.updatePosition(
                    namespace = namespace.value,
                    lastFile = currentFile,
                    lastOffset = currentOffset,
                    lastSequenceId = lastSeq
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is SyncHttpException && e.code == 404) {
                // Initial clean state: Server has no segment created for this namespace yet.
                // 0 inbound deltas to pull, proceed to push.
            } else {
                throw e
            }
        }

        return Pair(pulledFrames, pulledActions)
    }

    /**
     * Applies an inbound remote [CrdtAction] with deterministic Last-Write-Wins (LWW) resolution.
     */
    suspend fun applyRemoteAction(action: CrdtAction) {
        when (action) {
            is CrdtAction.AssetCreate -> {
                val existingAsset = mediaAssetDao.getAnyAssetById(action.fileId)
                val shouldApply = if (existingAsset == null) {
                    true
                } else {
                    isRemoteWinner(
                        remoteClock = action.lamportClock,
                        remoteDeviceId = action.deviceId,
                        localClock = existingAsset.lamportClock,
                        localDeviceId = existingAsset.deviceId
                    )
                }

                if (shouldApply) {
                    val asset = MediaAssetEntity(
                        fileId = action.fileId,
                        thumbnailHash = action.thumbnailCiphertextHash,
                        mediaType = action.mediaType,
                        width = action.width,
                        height = action.height,
                        durationMs = action.durationMs,
                        fileSize = action.fileSize,
                        deviceMake = action.deviceMake,
                        deviceModel = action.deviceModel,
                        lensModel = action.lensModel,
                        focalLength = action.focalLength,
                        aperture = action.aperture,
                        iso = action.iso,
                        exposureTime = action.exposureTime,
                        latitude = action.latitude,
                        longitude = action.longitude,
                        altitude = action.altitude,
                        capturedAt = action.capturedAt,
                        createdAt = action.timestampEpochMs,
                        updatedAt = action.timestampEpochMs,
                        assetTag = AssetTag.GALLERY_ITEM,
                        lamportClock = action.lamportClock,
                        deviceId = action.deviceId,
                        isDeleted = false
                    )
                    val crypto = MediaCryptoMappingEntity(
                        fileId = action.fileId,
                        plaintextSha256 = action.plaintextSha256,
                        ciphertextHash = action.ciphertextHash,
                        thumbnailCiphertextHash = action.thumbnailCiphertextHash,
                        wrappedDek = action.wrappedDek,
                        uploadStatus = UploadStatus.SYNCED,
                        lastSyncedAt = action.timestampEpochMs,
                        lamportClock = action.lamportClock,
                        deviceId = action.deviceId,
                        isDeleted = false
                    )
                    mediaAssetDao.upsert(asset)
                    mediaCryptoDao.upsert(crypto)
                }
            }

            is CrdtAction.AssetDelete -> {
                val existing = mediaAssetDao.getAnyAssetById(action.fileId)
                val shouldApply = if (existing == null) {
                    true
                } else {
                    isRemoteWinner(
                        remoteClock = action.lamportClock,
                        remoteDeviceId = action.deviceId,
                        localClock = existing.lamportClock,
                        localDeviceId = existing.deviceId
                    )
                }

                if (shouldApply) {
                    if (existing != null) {
                        mediaAssetDao.markDeleted(
                            fileId = action.fileId,
                            lamportClock = action.lamportClock,
                            deviceId = action.deviceId,
                            updatedAt = action.timestampEpochMs
                        )
                        mediaCryptoDao.markDeleted(
                            fileId = action.fileId,
                            lamportClock = action.lamportClock,
                            deviceId = action.deviceId
                        )
                    } else {
                        // Persist tombstone so replayed AssetCreate with lower clock is rejected
                        val tombstoneAsset = MediaAssetEntity(
                            fileId = action.fileId,
                            mediaType = "",
                            fileSize = 0L,
                            capturedAt = action.timestampEpochMs,
                            createdAt = action.timestampEpochMs,
                            updatedAt = action.timestampEpochMs,
                            lamportClock = action.lamportClock,
                            deviceId = action.deviceId,
                            isDeleted = true
                        )
                        mediaAssetDao.upsert(tombstoneAsset)
                    }
                }
            }

            is CrdtAction.AlbumCreate -> {
                val existing = albumDao.getAnyAlbumById(action.albumId)
                val shouldApply = if (existing == null) {
                    true
                } else {
                    isRemoteWinner(
                        remoteClock = action.lamportClock,
                        remoteDeviceId = action.deviceId,
                        localClock = existing.lamportClock,
                        localDeviceId = existing.deviceId
                    )
                }

                if (shouldApply) {
                    val album = AlbumEntity(
                        albumId = action.albumId,
                        title = action.title,
                        description = action.description,
                        coverFileId = action.coverFileId,
                        createdAt = action.timestampEpochMs,
                        updatedAt = action.timestampEpochMs,
                        lamportClock = action.lamportClock,
                        deviceId = action.deviceId,
                        isDeleted = false
                    )
                    albumDao.upsert(album)
                }
            }

            is CrdtAction.AlbumDelete -> {
                val existing = albumDao.getAnyAlbumById(action.albumId)
                val shouldApply = if (existing == null) {
                    true
                } else {
                    isRemoteWinner(
                        remoteClock = action.lamportClock,
                        remoteDeviceId = action.deviceId,
                        localClock = existing.lamportClock,
                        localDeviceId = existing.deviceId
                    )
                }

                if (shouldApply) {
                    if (existing != null) {
                        albumDao.markDeleted(
                            albumId = action.albumId,
                            lamportClock = action.lamportClock,
                            deviceId = action.deviceId,
                            updatedAt = action.timestampEpochMs
                        )
                    } else {
                        val tombstoneAlbum = AlbumEntity(
                            albumId = action.albumId,
                            title = "",
                            createdAt = action.timestampEpochMs,
                            updatedAt = action.timestampEpochMs,
                            lamportClock = action.lamportClock,
                            deviceId = action.deviceId,
                            isDeleted = true
                        )
                        albumDao.upsert(tombstoneAlbum)
                    }
                }
            }

            is CrdtAction.AlbumUpdateTitle -> {
                val existing = albumDao.getAnyAlbumById(action.albumId)
                val shouldApply = if (existing == null || existing.isDeleted) {
                    false
                } else {
                    isRemoteWinner(
                        remoteClock = action.lamportClock,
                        remoteDeviceId = action.deviceId,
                        localClock = existing.lamportClock,
                        localDeviceId = existing.deviceId
                    )
                }

                if (shouldApply) {
                    albumDao.updateAlbumDetailsWithClock(
                        albumId = action.albumId,
                        title = action.newTitle,
                        description = action.newDescription,
                        lamportClock = action.lamportClock,
                        deviceId = action.deviceId,
                        updatedAt = action.timestampEpochMs
                    )
                }
            }

            is CrdtAction.AlbumAddMedia -> {
                val album = albumDao.getAnyAlbumById(action.albumId)
                val asset = mediaAssetDao.getAnyAssetById(action.fileId)
                if (album?.isDeleted == true || asset?.isDeleted == true) {
                    return
                }

                val existing = albumDao.getAnyCrossRef(action.albumId, action.fileId)
                val shouldApply = if (existing == null) {
                    true
                } else {
                    isRemoteWinner(
                        remoteClock = action.lamportClock,
                        remoteDeviceId = action.deviceId,
                        localClock = existing.lamportClock,
                        localDeviceId = existing.deviceId
                    )
                }

                if (shouldApply) {
                    albumDao.insertCrossRef(
                        AlbumMediaCrossRef(
                            albumId = action.albumId,
                            fileId = action.fileId,
                            addedAt = action.timestampEpochMs,
                            lamportClock = action.lamportClock,
                            deviceId = action.deviceId,
                            isDeleted = false
                        )
                    )
                }
            }

            is CrdtAction.AlbumRemoveMedia -> {
                val existing = albumDao.getAnyCrossRef(action.albumId, action.fileId)
                val shouldApply = if (existing == null) {
                    true
                } else {
                    isRemoteWinner(
                        remoteClock = action.lamportClock,
                        remoteDeviceId = action.deviceId,
                        localClock = existing.lamportClock,
                        localDeviceId = existing.deviceId
                    )
                }

                if (shouldApply && existing != null) {
                    albumDao.markCrossRefDeleted(
                        albumId = action.albumId,
                        fileId = action.fileId,
                        lamportClock = action.lamportClock,
                        deviceId = action.deviceId
                    )
                }
            }
        }
    }

    /**
     * Inspects pending local actions in the queue, purging actions made invalid by incoming remote state,
     * and advancing Lamport clocks of remaining pending actions beyond the observed clock.
     */
    suspend fun reconcileQueue(namespace: String = "gallery"): Int {
        val pending = syncQueueDao.getAllPending(namespace)
        var purgedCount = 0
        val currentLocalClock = lamportClockManager.getClock()

        for (entry in pending) {
            val action = try {
                protoBuf.decodeFromByteArray(CrdtAction.serializer(), entry.payloadProtobuf)
            } catch (_: Exception) {
                null
            }

            var shouldPurge = false

            if (action != null) {
                when (action) {
                    is CrdtAction.AlbumAddMedia -> {
                        val album = albumDao.getAnyAlbumById(action.albumId)
                        val asset = mediaAssetDao.getAnyAssetById(action.fileId)
                        if (album?.isDeleted == true || asset?.isDeleted == true) {
                            shouldPurge = true
                        }
                    }
                    is CrdtAction.AlbumUpdateTitle -> {
                        val album = albumDao.getAnyAlbumById(action.albumId)
                        if (album?.isDeleted == true) {
                            shouldPurge = true
                        }
                    }
                    is CrdtAction.AlbumRemoveMedia -> {
                        val album = albumDao.getAnyAlbumById(action.albumId)
                        if (album?.isDeleted == true) {
                            shouldPurge = true
                        }
                    }
                    else -> {}
                }
            }

            if (shouldPurge) {
                syncQueueDao.deleteById(entry.queueId)
                purgedCount++
            } else if (action != null && entry.lamportClock <= currentLocalClock) {
                val newClock = lamportClockManager.tick()
                val reClocked = reclockAction(action, newClock)
                val newBytes = protoBuf.encodeToByteArray(CrdtAction.serializer(), reClocked)
                syncQueueDao.updateClockAndPayload(
                    queueId = entry.queueId,
                    newClock = newClock,
                    payloadProtobuf = newBytes,
                    byteSize = newBytes.size
                )
                // Synchronize local Room entity clock with re-clocked action
                when (reClocked) {
                    is CrdtAction.AssetCreate -> {
                        val asset = mediaAssetDao.getAnyAssetById(reClocked.fileId)
                        if (asset != null && asset.lamportClock < newClock) {
                            mediaAssetDao.upsert(asset.copy(lamportClock = newClock))
                        }
                        val crypto = mediaCryptoDao.getAnyByFileId(reClocked.fileId)
                        if (crypto != null && crypto.lamportClock < newClock) {
                            mediaCryptoDao.upsert(crypto.copy(lamportClock = newClock))
                        }
                    }
                    is CrdtAction.AlbumCreate -> {
                        val album = albumDao.getAnyAlbumById(reClocked.albumId)
                        if (album != null && album.lamportClock < newClock) {
                            albumDao.upsert(album.copy(lamportClock = newClock))
                        }
                    }
                    is CrdtAction.AlbumAddMedia -> {
                        val ref = albumDao.getAnyCrossRef(reClocked.albumId, reClocked.fileId)
                        if (ref != null && ref.lamportClock < newClock) {
                            albumDao.insertCrossRef(ref.copy(lamportClock = newClock))
                        }
                    }
                    else -> {}
                }
            }
        }
        return purgedCount
    }

    /**
     * Slices pending queue actions into bundles of <= 512 KiB, encrypts with $K_{ledger}$,
     * and uploads to the server committer.
     */
    suspend fun pushOutboundSlices(
        namespace: SyncNamespace,
        ledgerKey: ByteArray
    ): Pair<Int, Int> {
        var pushedBundles = 0
        var pushedActions = 0

        while (true) {
            val pendingEntries = syncQueueDao.getPendingActions(namespace.value, limit = 100)
            if (pendingEntries.isEmpty()) break

            val batch = mutableListOf<SyncQueueEntity>()
            val actions = mutableListOf<CrdtAction>()
            var accumulatedSize = 0

            for (entry in pendingEntries) {
                val action = try {
                    protoBuf.decodeFromByteArray(CrdtAction.serializer(), entry.payloadProtobuf)
                } catch (_: Exception) {
                    syncQueueDao.deleteById(entry.queueId)
                    continue
                }
                val actionSize = entry.byteSize

                if (batch.isNotEmpty() && accumulatedSize + actionSize > MAX_SLICE_SIZE) {
                    break
                }

                batch.add(entry)
                actions.add(action)
                accumulatedSize += actionSize
            }

            if (batch.isEmpty()) break

            val minClock = actions.minOf { it.lamportClock }
            val maxClock = actions.maxOf { it.lamportClock }
            val bundleId = UUID.randomUUID().toString()
            val bundle = SyncDeltaBundle(
                bundleId = bundleId,
                deviceId = lamportClockManager.getDeviceId(),
                minClock = minClock,
                maxClock = maxClock,
                actions = actions
            )

            val bundleBytes = protoBuf.encodeToByteArray(SyncDeltaBundle.serializer(), bundle)
            val encryptedPayload = LedgerCrypto.encryptBundle(bundleBytes, ledgerKey)

            val appendResult = syncClient.appendDelta(namespace, encryptedPayload)
            when (appendResult) {
                is NetworkResult.Success -> {
                    pushedBundles++
                    pushedActions += actions.size

                    val batchIds = batch.map { it.queueId }
                    syncQueueDao.deleteByIds(batchIds)
                }
                is NetworkResult.Failure -> {
                    throw IOException("Failed to append sync delta slice: $appendResult")
                }
            }
        }

        return Pair(pushedBundles, pushedActions)
    }

    /**
     * Re-clocks a [CrdtAction] with a new monotonically advanced Lamport clock.
     */
    private fun reclockAction(action: CrdtAction, newClock: Long): CrdtAction {
        return when (action) {
            is CrdtAction.AssetCreate -> action.copy(lamportClock = newClock)
            is CrdtAction.AssetDelete -> action.copy(lamportClock = newClock)
            is CrdtAction.AlbumCreate -> action.copy(lamportClock = newClock)
            is CrdtAction.AlbumDelete -> action.copy(lamportClock = newClock)
            is CrdtAction.AlbumUpdateTitle -> action.copy(lamportClock = newClock)
            is CrdtAction.AlbumAddMedia -> action.copy(lamportClock = newClock)
            is CrdtAction.AlbumRemoveMedia -> action.copy(lamportClock = newClock)
        }
    }

    /**
     * Evaluates Lamport clock and device ID tie-breaker:
     * (L_remote, device_remote) > (L_local, device_local)
     */
    fun isRemoteWinner(
        remoteClock: Long,
        remoteDeviceId: String,
        localClock: Long,
        localDeviceId: String
    ): Boolean {
        if (remoteClock > localClock) return true
        if (remoteClock < localClock) return false
        return remoteDeviceId > localDeviceId
    }
}
