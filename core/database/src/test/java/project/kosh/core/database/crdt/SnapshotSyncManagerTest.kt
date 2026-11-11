package project.kosh.core.database.crdt

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import project.kosh.core.crypto.AesGcmEnvelope
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.database.KoshDatabase
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.entity.AlbumMediaCrossRef
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.database.entity.SyncWatermarkEntity
import project.kosh.core.model.AssetTag
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.SnapshotResponse
import project.kosh.core.model.SyncNamespace
import project.kosh.core.model.UploadStatus
import project.kosh.core.model.snapshot.GallerySnapshotBundle
import project.kosh.core.model.snapshot.SnapshotAlbum
import project.kosh.core.model.snapshot.SnapshotAlbumCrossRef
import project.kosh.core.model.snapshot.SnapshotMediaAsset
import project.kosh.core.model.snapshot.SnapshotMediaCrypto
import project.kosh.core.network.AssetClient
import project.kosh.core.network.SyncClient

@OptIn(ExperimentalSerializationApi::class)
@RunWith(RobolectricTestRunner::class)
class SnapshotSyncManagerTest {

    private lateinit var db: KoshDatabase
    private lateinit var syncClient: SyncClient
    private lateinit var assetClient: AssetClient
    private lateinit var manager: SnapshotSyncManager
    private val masterVaultKey = MasterVaultKey(ByteArray(32) { 9.toByte() })
    private val protoBuf = ProtoBuf { }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KoshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncClient = mockk(relaxed = true)
        assetClient = mockk(relaxed = true)

        manager = SnapshotSyncManager(
            syncClient = syncClient,
            assetClient = assetClient,
            mediaAssetDao = db.mediaAssetDao(),
            mediaCryptoDao = db.mediaCryptoDao(),
            albumDao = db.albumDao(),
            syncWatermarkDao = db.syncWatermarkDao(),
            database = db,
            context = context
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testCreateAndUploadSnapshot_withData_success() = runBlocking {
        // Arrange
        val asset = MediaAssetEntity(
            fileId = "asset-1",
            mediaType = "image/jpeg",
            fileSize = 1024L,
            capturedAt = 1000L
        )
        val crypto = MediaCryptoMappingEntity(
            fileId = "asset-1",
            plaintextSha256 = "sha-1",
            ciphertextHash = "cas-1",
            wrappedDek = "01".repeat(60),
            uploadStatus = UploadStatus.SYNCED
        )
        val album = AlbumEntity(
            albumId = "album-1",
            title = "Test Album"
        )
        val crossRef = AlbumMediaCrossRef(
            albumId = "album-1",
            fileId = "asset-1",
            positionIndex = 0
        )

        db.mediaAssetDao().insert(asset)
        db.mediaCryptoDao().insert(crypto)
        db.albumDao().insert(album)
        db.albumDao().insertCrossRef(crossRef)
        db.syncWatermarkDao().upsertWatermark(
            SyncWatermarkEntity(
                namespace = "gallery",
                lastSequenceId = 42L
            )
        )

        val uploadedBytesSlot = slot<ByteArray>()
        coEvery {
            assetClient.uploadBytes(
                bytes = capture(uploadedBytesSlot),
                tag = AssetTag.GALLERY_META,
                originalFileName = "snapshot_gallery_42.bin",
                onProgress = any()
            )
        } returns NetworkResult.Success("blob-blake3-hash-42")

        coEvery {
            syncClient.registerSnapshot(
                namespace = SyncNamespace.GALLERY,
                snapshotSeq = 42L,
                blobHash = "blob-blake3-hash-42"
            )
        } returns NetworkResult.Success(100L)

        // Act
        val result = manager.createAndUploadSnapshot(masterVaultKey)

        // Assert
        assertTrue(result is NetworkResult.Success)
        assertEquals(100L, (result as NetworkResult.Success).data)

        // Verify uploaded payload was properly encrypted and decodable
        val rawEncrypted = uploadedBytesSlot.captured
        val decrypted = AesGcmEnvelope.decrypt(rawEncrypted, masterVaultKey.keyBytes)
        val bundle = protoBuf.decodeFromByteArray(GallerySnapshotBundle.serializer(), decrypted)

        assertEquals(1, bundle.version)
        assertEquals(42L, bundle.watermarkSeq)
        assertEquals(1, bundle.assets.size)
        assertEquals("asset-1", bundle.assets[0].fileId)
        assertEquals(1, bundle.cryptoMappings.size)
        assertEquals("sha-1", bundle.cryptoMappings[0].plaintextSha256)
        assertEquals(1, bundle.albums.size)
        assertEquals("Test Album", bundle.albums[0].title)
        assertEquals(1, bundle.albumCrossRefs.size)
        assertEquals("album-1", bundle.albumCrossRefs[0].albumId)
    }

    @Test
    fun testCreateAndUploadSnapshot_emptyDb_returnsZeroEarly() = runBlocking {
        val result = manager.createAndUploadSnapshot(masterVaultKey)
        assertTrue(result is NetworkResult.Success)
        assertEquals(0L, (result as NetworkResult.Success).data)
        coVerify(exactly = 0) { assetClient.uploadBytes(any(), any(), any(), any()) }
        coVerify(exactly = 0) { syncClient.registerSnapshot(any(), any(), any()) }
    }

    @Test
    fun testRestoreFromLatestSnapshot_success() = runBlocking {
        // Arrange
        val assetDto = SnapshotMediaAsset(
            fileId = "restored-file-1",
            mediaType = "image/png",
            fileSize = 2048L,
            capturedAt = 2000L
        )
        val cryptoDto = SnapshotMediaCrypto(
            fileId = "restored-file-1",
            plaintextSha256 = "sha-restored",
            ciphertextHash = "cas-restored",
            wrappedDek = "02".repeat(60),
            uploadStatus = UploadStatus.SYNCED
        )
        val albumDto = SnapshotAlbum(
            albumId = "restored-album-1",
            title = "Restored Album"
        )
        val crossRefDto = SnapshotAlbumCrossRef(
            albumId = "restored-album-1",
            fileId = "restored-file-1",
            positionIndex = 0
        )

        val bundle = GallerySnapshotBundle(
            version = 1,
            generatedAtEpochMs = 1700000000000L,
            watermarkSeq = 88L,
            assets = listOf(assetDto),
            cryptoMappings = listOf(cryptoDto),
            albums = listOf(albumDto),
            albumCrossRefs = listOf(crossRefDto)
        )

        val payload = protoBuf.encodeToByteArray(GallerySnapshotBundle.serializer(), bundle)
        val encryptedPayload = AesGcmEnvelope.encrypt(payload, masterVaultKey.keyBytes)

        coEvery {
            syncClient.getLatestSnapshot(SyncNamespace.GALLERY)
        } returns NetworkResult.Success(
            SnapshotResponse(
                id = 10L,
                snapshotSeq = 88L,
                blobHash = "blob-hash-88",
                createdAt = 1700000000L
            )
        )

        coEvery {
            assetClient.downloadBlob("blob-hash-88")
        } returns NetworkResult.Success(encryptedPayload)

        // Act
        val result = manager.restoreFromLatestSnapshot(masterVaultKey)

        // Assert
        assertTrue(result is NetworkResult.Success)
        assertTrue((result as NetworkResult.Success).data)

        // Verify database state
        val persistedAsset = db.mediaAssetDao().getAssetById("restored-file-1")
        assertNotNull(persistedAsset)
        assertEquals("image/png", persistedAsset?.mediaType)

        val persistedCrypto = db.mediaCryptoDao().getByFileId("restored-file-1")
        assertNotNull(persistedCrypto)
        assertEquals("sha-restored", persistedCrypto?.plaintextSha256)

        val persistedAlbum = db.albumDao().getAlbumById("restored-album-1")
        assertNotNull(persistedAlbum)
        assertEquals("Restored Album", persistedAlbum?.title)

        val crossRefs = db.albumDao().getAllCrossRefs()
        assertEquals(1, crossRefs.size)
        assertEquals("restored-album-1", crossRefs[0].albumId)

        val watermark = db.syncWatermarkDao().getWatermark("gallery")
        assertNotNull(watermark)
        assertEquals(88L, watermark?.lastSequenceId)
    }

    @Test
    fun testRestoreFromLatestSnapshot_localAhead_skipsRestore() = runBlocking {
        // Local database already at seq 100 with 5 assets
        db.mediaAssetDao().insert(
            MediaAssetEntity(
                fileId = "existing-1",
                mediaType = "image/jpeg",
                fileSize = 500L,
                capturedAt = 100L
            )
        )
        db.syncWatermarkDao().upsertWatermark(
            SyncWatermarkEntity(
                namespace = "gallery",
                lastSequenceId = 100L
            )
        )

        coEvery {
            syncClient.getLatestSnapshot(SyncNamespace.GALLERY)
        } returns NetworkResult.Success(
            SnapshotResponse(
                id = 5L,
                snapshotSeq = 90L, // server is behind local watermark
                blobHash = "blob-hash-90",
                createdAt = 1699999999L
            )
        )

        val result = manager.restoreFromLatestSnapshot(masterVaultKey)
        assertTrue(result is NetworkResult.Success)
        assertFalse((result as NetworkResult.Success).data)

        coVerify(exactly = 0) { assetClient.downloadBlob(any()) }
    }

    @Test
    fun testRestoreFromLatestSnapshot_noSnapshotOnServer() = runBlocking {
        coEvery {
            syncClient.getLatestSnapshot(SyncNamespace.GALLERY)
        } returns NetworkResult.Success(null)

        val result = manager.restoreFromLatestSnapshot(masterVaultKey)
        assertTrue(result is NetworkResult.Success)
        assertFalse((result as NetworkResult.Success).data)
    }

    @Test
    fun testIsLocalNetworkEndpoint() {
        assertTrue(manager.isLocalNetworkEndpoint("http://localhost:6969"))
        assertTrue(manager.isLocalNetworkEndpoint("https://127.0.0.1:6969"))
        assertTrue(manager.isLocalNetworkEndpoint("http://192.168.1.50:6969/api/v1"))
        assertTrue(manager.isLocalNetworkEndpoint("https://10.0.2.2:6969"))
        assertTrue(manager.isLocalNetworkEndpoint("http://172.16.0.1:6969"))
        assertTrue(manager.isLocalNetworkEndpoint("http://172.31.255.1:6969"))
        assertTrue(manager.isLocalNetworkEndpoint("https://kosh-vault.local:6969"))

        assertFalse(manager.isLocalNetworkEndpoint("https://api.koshvault.io"))
        assertFalse(manager.isLocalNetworkEndpoint("https://8.8.8.8:6969"))
        assertFalse(manager.isLocalNetworkEndpoint("https://172.32.0.1:6969"))
        assertFalse(manager.isLocalNetworkEndpoint("https://1.1.1.1:6969"))

        // Without scheme
        assertTrue(manager.isLocalNetworkEndpoint("192.168.1.1:6969"))
        assertTrue(manager.isLocalNetworkEndpoint("localhost:6969"))

        // IPv6 loopback
        assertTrue(manager.isLocalNetworkEndpoint("http://[::1]:6969"))

        // Trailing dot mDNS
        assertTrue(manager.isLocalNetworkEndpoint("https://kosh-vault.local.:6969"))
    }

    @Test
    fun testRestoreFromLatestSnapshot_corruptBlob_returnsFailureAndLeavesDatabaseClean() = runBlocking {
        coEvery {
            syncClient.getLatestSnapshot(SyncNamespace.GALLERY)
        } returns NetworkResult.Success(
            SnapshotResponse(
                id = 11L,
                snapshotSeq = 50L,
                blobHash = "blob-corrupt-50",
                createdAt = 1700000000L
            )
        )

        // Return truncated/garbage bytes that fail AES-GCM decryption
        coEvery {
            assetClient.downloadBlob("blob-corrupt-50")
        } returns NetworkResult.Success(byteArrayOf(0x01, 0x02, 0x03, 0x04))

        val result = manager.restoreFromLatestSnapshot(masterVaultKey)
        assertTrue(result is NetworkResult.Failure)
        assertEquals(0, db.mediaAssetDao().getCount())
    }

    @Test
    fun testRestoreFromLatestSnapshot_corruptProtobuf_returnsFailure() = runBlocking {
        // Encrypt invalid protobuf bytes
        val garbageEncrypted = AesGcmEnvelope.encrypt(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()), masterVaultKey.keyBytes)

        coEvery {
            syncClient.getLatestSnapshot(SyncNamespace.GALLERY)
        } returns NetworkResult.Success(
            SnapshotResponse(
                id = 12L,
                snapshotSeq = 60L,
                blobHash = "blob-bad-proto",
                createdAt = 1700000000L
            )
        )

        coEvery {
            assetClient.downloadBlob("blob-bad-proto")
        } returns NetworkResult.Success(garbageEncrypted)

        val result = manager.restoreFromLatestSnapshot(masterVaultKey)
        assertTrue(result is NetworkResult.Failure)
        assertEquals(0, db.mediaAssetDao().getCount())
    }

    @Test
    fun testRestoreFromLatestSnapshot_orphanedForeignKeys_sanitizesAndInsertsCleanly() = runBlocking {
        val assetDto = SnapshotMediaAsset(
            fileId = "asset-exists",
            mediaType = "image/jpeg",
            fileSize = 1000L,
            capturedAt = 1000L
        )
        // Crypto pointing to a non-existent asset
        val orphanedCrypto = SnapshotMediaCrypto(
            fileId = "asset-does-not-exist",
            plaintextSha256 = "sha-orphan",
            wrappedDek = "01".repeat(60)
        )
        // Album referencing non-existent cover asset
        val albumDto = SnapshotAlbum(
            albumId = "album-orphan-cover",
            title = "Album with Orphan Cover",
            coverFileId = "non-existent-cover-id"
        )
        // Cross ref referencing non-existent asset
        val orphanedCrossRef = SnapshotAlbumCrossRef(
            albumId = "album-orphan-cover",
            fileId = "non-existent-asset-id"
        )

        val bundle = GallerySnapshotBundle(
            version = 1,
            generatedAtEpochMs = 1700000000000L,
            watermarkSeq = 70L,
            assets = listOf(assetDto),
            cryptoMappings = listOf(orphanedCrypto),
            albums = listOf(albumDto),
            albumCrossRefs = listOf(orphanedCrossRef)
        )

        val payload = protoBuf.encodeToByteArray(GallerySnapshotBundle.serializer(), bundle)
        val encryptedPayload = AesGcmEnvelope.encrypt(payload, masterVaultKey.keyBytes)

        coEvery {
            syncClient.getLatestSnapshot(SyncNamespace.GALLERY)
        } returns NetworkResult.Success(
            SnapshotResponse(
                id = 13L,
                snapshotSeq = 70L,
                blobHash = "blob-orphan-test",
                createdAt = 1700000000L
            )
        )

        coEvery {
            assetClient.downloadBlob("blob-orphan-test")
        } returns NetworkResult.Success(encryptedPayload)

        val result = manager.restoreFromLatestSnapshot(masterVaultKey)
        assertTrue(result is NetworkResult.Success)
        assertTrue((result as NetworkResult.Success).data)

        // Verify valid asset and album are stored, coverFileId is sanitized to null, orphaned crypto and cross ref were skipped
        assertNotNull(db.mediaAssetDao().getAssetById("asset-exists"))
        val persistedAlbum = db.albumDao().getAlbumById("album-orphan-cover")
        assertNotNull(persistedAlbum)
        assertEquals(null, persistedAlbum?.coverFileId)
        assertEquals(0, db.albumDao().getAllCrossRefs().size)
        assertEquals(null, db.mediaCryptoDao().getByFileId("asset-does-not-exist"))
    }

    @Test
    fun testClosedKey_returnsFailure() = runBlocking {
        val closedKey = MasterVaultKey(ByteArray(32) { 1.toByte() })
        closedKey.close()

        val createResult = manager.createAndUploadSnapshot(closedKey)
        assertTrue(createResult is NetworkResult.Failure)

        val restoreResult = manager.restoreFromLatestSnapshot(closedKey)
        assertTrue(restoreResult is NetworkResult.Failure)
    }
}
