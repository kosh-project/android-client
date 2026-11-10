package project.kosh.core.database.crdt

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import project.kosh.core.crypto.LamportClockManager
import project.kosh.core.crypto.LedgerCrypto
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.database.KoshDatabase
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.SyncQueueEntity
import project.kosh.core.model.DeltaAppendResponse
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.SyncFrame
import project.kosh.core.model.SyncNamespace
import project.kosh.core.model.crdt.CrdtAction
import project.kosh.core.model.crdt.SyncDeltaBundle
import project.kosh.core.network.SyncClient

@OptIn(ExperimentalSerializationApi::class)
@RunWith(RobolectricTestRunner::class)
class CrdtSyncEngineTest {

    private lateinit var db: KoshDatabase
    private lateinit var syncClient: SyncClient
    private lateinit var clockManager: LamportClockManager
    private lateinit var engine: CrdtSyncEngine
    private val masterVaultKey = MasterVaultKey(ByteArray(32) { 7.toByte() })
    private val protoBuf = ProtoBuf { }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KoshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncClient = mockk(relaxed = true)
        clockManager = LamportClockManager(initialClock = 10L, testDeviceId = "dev-local")

        engine = CrdtSyncEngine(
            syncClient = syncClient,
            mediaAssetDao = db.mediaAssetDao(),
            mediaCryptoDao = db.mediaCryptoDao(),
            albumDao = db.albumDao(),
            syncQueueDao = db.syncQueueDao(),
            syncWatermarkDao = db.syncWatermarkDao(),
            lamportClockManager = clockManager,
            database = db
        )
    }

    @After
    fun tearDown() {
        db.close()
        masterVaultKey.close()
    }

    @Test
    fun isRemoteWinner_tieBreakerLogic() {
        // Higher clock wins
        assertTrue(engine.isRemoteWinner(remoteClock = 15L, remoteDeviceId = "a", localClock = 10L, localDeviceId = "z"))
        assertFalse(engine.isRemoteWinner(remoteClock = 10L, remoteDeviceId = "z", localClock = 15L, localDeviceId = "a"))

        // Equal clock: device ID tie-breaker
        assertTrue(engine.isRemoteWinner(remoteClock = 20L, remoteDeviceId = "dev-remote", localClock = 20L, localDeviceId = "dev-local"))
        assertFalse(engine.isRemoteWinner(remoteClock = 20L, remoteDeviceId = "dev-local", localClock = 20L, localDeviceId = "dev-remote"))
    }

    @Test
    fun pullInboundDeltas_appliesAssetCreateAndAdvancesClock() = runBlocking {
        val ledgerKey = LedgerCrypto.deriveLedgerKey(masterVaultKey)

        val remoteAction = CrdtAction.AssetCreate(
            actionId = "act-remote-1",
            lamportClock = 42L,
            deviceId = "dev-peer",
            timestampEpochMs = 1710000000000L,
            fileId = "remote-file-1",
            mediaType = "image/jpeg",
            fileSize = 4096L,
            capturedAt = 1710000000000L,
            plaintextSha256 = "sha256-remote-asset",
            ciphertextHash = "cas-remote-blake3",
            thumbnailCiphertextHash = "cas-remote-thumb",
            wrappedDek = "0".repeat(120)
        )

        val bundle = SyncDeltaBundle(
            bundleId = "bundle-1",
            deviceId = "dev-peer",
            minClock = 42L,
            maxClock = 42L,
            actions = listOf(remoteAction)
        )
        val bundleBytes = protoBuf.encodeToByteArray(SyncDeltaBundle.serializer(), bundle)
        val encryptedPayload = LedgerCrypto.encryptBundle(bundleBytes, ledgerKey)

        val syncFrame = SyncFrame(
            length = encryptedPayload.size,
            sequenceId = 1001L,
            payload = encryptedPayload
        )

        coEvery {
            syncClient.streamDeltas(SyncNamespace.GALLERY, fileName = any(), offset = any())
        } returns flowOf(syncFrame)

        val (pulledFrames, pulledActions) = engine.pullInboundDeltas(SyncNamespace.GALLERY, ledgerKey)

        assertEquals(1, pulledFrames)
        assertEquals(1, pulledActions)

        // Asset must be persisted in database
        val asset = db.mediaAssetDao().getAssetById("remote-file-1")
        assertNotNull(asset)
        assertEquals("remote-file-1", asset?.fileId)
        assertEquals(42L, asset?.lamportClock)

        val crypto = db.mediaCryptoDao().getByFileId("remote-file-1")
        assertNotNull(crypto)
        assertEquals("sha256-remote-asset", crypto?.plaintextSha256)
        assertEquals("cas-remote-blake3", crypto?.ciphertextHash)

        // Local clock must advance to max(local, remote) + 1 = 43
        assertTrue(clockManager.getClock() >= 43L)

        // Watermark position must be updated
        val watermark = db.syncWatermarkDao().getWatermark("gallery")
        assertNotNull(watermark)
        assertEquals(1001L, watermark?.lastSequenceId)
    }

    @Test
    fun reconcileQueue_purgesActionsWhenAlbumDeletedRemotely() = runBlocking {
        // Insert an album that has been remotely deleted
        db.albumDao().insert(
            AlbumEntity(
                albumId = "alb-deleted",
                title = "Deleted Album",
                isDeleted = true,
                lamportClock = 50L
            )
        )

        // Enqueue an action referencing this deleted album
        val addAction = CrdtAction.AlbumAddMedia(
            actionId = "act-add-media",
            lamportClock = 15L,
            deviceId = "dev-local",
            timestampEpochMs = 1710000010000L,
            albumId = "alb-deleted",
            fileId = "f1"
        )
        val actionBytes = protoBuf.encodeToByteArray(CrdtAction.serializer(), addAction)
        val queueEntry = SyncQueueEntity(
            actionId = addAction.actionId,
            namespace = "gallery",
            actionType = "AlbumAddMedia",
            entityId = "alb-deleted",
            payloadProtobuf = actionBytes,
            byteSize = actionBytes.size,
            lamportClock = addAction.lamportClock,
            status = "PENDING"
        )
        db.syncQueueDao().enqueue(queueEntry)

        assertEquals(1, db.syncQueueDao().getAllPending().size)

        val purged = engine.reconcileQueue("gallery")
        assertEquals(1, purged)
        assertEquals(0, db.syncQueueDao().getAllPending().size)
    }

    @Test
    fun pushOutboundSlices_enforces512KbBoundary() = runBlocking {
        val ledgerKey = LedgerCrypto.deriveLedgerKey(masterVaultKey)

        // Create large payload actions each around 200 KiB to cross the 512 KiB threshold
        val largePadding = "x".repeat(200 * 1024)
        for (i in 1..4) {
            val action = CrdtAction.AlbumCreate(
                actionId = "act-$i",
                lamportClock = (10 + i).toLong(),
                deviceId = "dev-local",
                timestampEpochMs = System.currentTimeMillis(),
                albumId = "album-$i",
                title = "Album $i",
                description = largePadding
            )
            val bytes = protoBuf.encodeToByteArray(CrdtAction.serializer(), action)
            db.syncQueueDao().enqueue(
                SyncQueueEntity(
                    actionId = action.actionId,
                    namespace = "gallery",
                    actionType = "AlbumCreate",
                    entityId = action.albumId,
                    payloadProtobuf = bytes,
                    byteSize = bytes.size,
                    lamportClock = action.lamportClock
                )
            )
        }

        assertEquals(4, db.syncQueueDao().getAllPending().size)

        val capturedPayloads = mutableListOf<ByteArray>()
        coEvery {
            syncClient.appendDelta(SyncNamespace.GALLERY, capture(capturedPayloads))
        } answers {
            NetworkResult.Success(DeltaAppendResponse(sequenceId = 200L, fileName = "current.log", offset = 10000L))
        }

        val (bundlesCount, actionsCount) = engine.pushOutboundSlices(SyncNamespace.GALLERY, ledgerKey)

        // 4 actions of ~200 KiB each = ~800 KiB total -> must be sliced into at least 2 bundles!
        assertTrue("Expected at least 2 slices for 800KB payload, got $bundlesCount", bundlesCount >= 2)
        assertEquals(4, actionsCount)

        // Verify each encrypted bundle slice payload does not exceed MAX_SLICE_SIZE (+ envelope overhead)
        for (payload in capturedPayloads) {
            assertTrue("Payload slice size (${payload.size}) must be <= 512KB + 28B envelope", payload.size <= CrdtSyncEngine.MAX_SLICE_SIZE + 28)
        }

        // All items in queue should now be cleared
        assertEquals(0, db.syncQueueDao().getAllPending().size)
    }

    @Test(expected = project.kosh.core.network.SyncHttpException::class)
    fun pullInboundDeltas_propagatesExceptionOnStreamFailure() {
        runBlocking {
            val ledgerKey = LedgerCrypto.deriveLedgerKey(masterVaultKey)
            coEvery {
                syncClient.streamDeltas(SyncNamespace.GALLERY, any(), any())
            } returns kotlinx.coroutines.flow.flow {
                throw project.kosh.core.network.SyncHttpException(401, "Unauthorized")
            }

            engine.pullInboundDeltas(SyncNamespace.GALLERY, ledgerKey)
        }
    }

    @Test
    fun applyRemoteAction_assetDeleteOnNonExistentEntity_persistsTombstoneAndRejectsOlderCreate() = runBlocking {
        // Asset does not exist locally. Remote delete arrives at clock 50.
        val deleteAction = CrdtAction.AssetDelete(
            actionId = "act-del",
            lamportClock = 50L,
            deviceId = "dev-peer",
            timestampEpochMs = 1710000000000L,
            fileId = "file-never-seen"
        )
        engine.applyRemoteAction(deleteAction)

        // Tombstone must be in DB
        val tombstone = db.mediaAssetDao().getAnyAssetById("file-never-seen")
        assertNotNull(tombstone)
        assertTrue(tombstone!!.isDeleted)
        assertEquals(50L, tombstone.lamportClock)

        // Now an older AssetCreate arrives with clock 40
        val createAction = CrdtAction.AssetCreate(
            actionId = "act-create-old",
            lamportClock = 40L,
            deviceId = "dev-peer",
            timestampEpochMs = 1709999990000L,
            fileId = "file-never-seen",
            mediaType = "image/jpeg",
            fileSize = 1000L,
            capturedAt = 1000L,
            plaintextSha256 = "hash",
            ciphertextHash = "cipher",
            wrappedDek = "0".repeat(120)
        )
        engine.applyRemoteAction(createAction)

        // Asset must NOT be resurrected; tombstone must remain
        val afterCreate = db.mediaAssetDao().getAnyAssetById("file-never-seen")
        assertNotNull(afterCreate)
        assertTrue(afterCreate!!.isDeleted)
        assertEquals(50L, afterCreate.lamportClock)
        assertNull(db.mediaAssetDao().getAssetById("file-never-seen"))
    }

    @Test
    fun applyRemoteAction_albumAddMedia_rejectsWhenAlbumDeleted() = runBlocking {
        // Create an album and mark it deleted
        db.albumDao().insert(
            AlbumEntity(
                albumId = "alb-del",
                title = "Deleted",
                isDeleted = true,
                lamportClock = 20L
            )
        )
        db.mediaAssetDao().insert(
            MediaAssetEntity(
                fileId = "f-alive",
                mediaType = "image/jpeg",
                fileSize = 100L,
                capturedAt = 100L
            )
        )

        val addAction = CrdtAction.AlbumAddMedia(
            actionId = "act-add",
            lamportClock = 25L,
            deviceId = "dev-peer",
            timestampEpochMs = 2000L,
            albumId = "alb-del",
            fileId = "f-alive"
        )
        engine.applyRemoteAction(addAction)

        // Cross ref must NOT be created
        assertNull(db.albumDao().getAnyCrossRef("alb-del", "f-alive"))
    }

    @Test
    fun pushOutboundSlices_doesNotOverwriteReadWatermark() = runBlocking {
        val ledgerKey = LedgerCrypto.deriveLedgerKey(masterVaultKey)

        // Set initial read watermark at offset 500, sequence 10
        db.syncWatermarkDao().upsertWatermark(
            project.kosh.core.database.entity.SyncWatermarkEntity(
                namespace = "gallery",
                lastFile = "current.log",
                lastOffset = 500L,
                lastSequenceId = 10L
            )
        )

        // Queue a pending action
        val action = CrdtAction.AlbumCreate(
            actionId = "act-1",
            lamportClock = 11L,
            deviceId = "dev-local",
            timestampEpochMs = 1000L,
            albumId = "alb-1",
            title = "My Album"
        )
        val bytes = protoBuf.encodeToByteArray(CrdtAction.serializer(), action)
        db.syncQueueDao().enqueue(
            SyncQueueEntity(
                actionId = action.actionId,
                namespace = "gallery",
                actionType = "AlbumCreate",
                entityId = action.albumId,
                payloadProtobuf = bytes,
                byteSize = bytes.size,
                lamportClock = action.lamportClock
            )
        )

        coEvery {
            syncClient.appendDelta(SyncNamespace.GALLERY, any())
        } returns NetworkResult.Success(DeltaAppendResponse(sequenceId = 50L, fileName = "current.log", offset = 9999L))

        engine.pushOutboundSlices(SyncNamespace.GALLERY, ledgerKey)

        // Read watermark must NOT have jumped to 9999L / 50L!
        val watermark = db.syncWatermarkDao().getWatermark("gallery")
        assertNotNull(watermark)
        assertEquals(500L, watermark?.lastOffset)
        assertEquals(10L, watermark?.lastSequenceId)
    }

    @Test
    fun reconcileQueue_synchronizesEntityClockInRoom() = runBlocking {
        // Local asset created at clock 5
        db.mediaAssetDao().insert(
            MediaAssetEntity(
                fileId = "file-clock-sync",
                mediaType = "image/jpeg",
                fileSize = 1000L,
                capturedAt = 1000L,
                lamportClock = 5L
            )
        )

        val action = CrdtAction.AssetCreate(
            actionId = "act-clock",
            lamportClock = 5L,
            deviceId = "dev-local",
            timestampEpochMs = 1000L,
            fileId = "file-clock-sync",
            mediaType = "image/jpeg",
            fileSize = 1000L,
            capturedAt = 1000L,
            plaintextSha256 = "hash",
            ciphertextHash = "cipher",
            wrappedDek = "0".repeat(120)
        )
        val bytes = protoBuf.encodeToByteArray(CrdtAction.serializer(), action)
        db.syncQueueDao().enqueue(
            SyncQueueEntity(
                actionId = action.actionId,
                namespace = "gallery",
                actionType = "AssetCreate",
                entityId = action.fileId,
                payloadProtobuf = bytes,
                byteSize = bytes.size,
                lamportClock = 5L
            )
        )

        // Remote events advance local clock to 20
        clockManager.update(20L) // clock is now 21

        engine.reconcileQueue("gallery")

        // Queued action clock re-clocked to > 21
        val queued = db.syncQueueDao().getAllPending("gallery").first()
        assertTrue("Queued action clock must be > 21, was ${queued.lamportClock}", queued.lamportClock > 21L)

        // Room entity clock must also be synchronized to the new clock
        val roomAsset = db.mediaAssetDao().getAnyAssetById("file-clock-sync")
        assertNotNull(roomAsset)
        assertEquals(queued.lamportClock, roomAsset?.lamportClock)
    }
}
