package project.kosh.core.database.crdt

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import project.kosh.core.crypto.LamportClockManager
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.crypto.MediaHasher
import project.kosh.core.database.KoshDatabase
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.model.AssetTag
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.UploadStatus
import project.kosh.core.model.crdt.CrdtAction
import project.kosh.core.network.AssetClient

@OptIn(ExperimentalSerializationApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaUploadPipelineTest {

    private lateinit var db: KoshDatabase
    private lateinit var assetClient: AssetClient
    private lateinit var clockManager: LamportClockManager
    private lateinit var pipeline: MediaUploadPipeline
    private val masterVaultKey = MasterVaultKey(ByteArray(32) { 9.toByte() })
    private val protoBuf = ProtoBuf { }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KoshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assetClient = mockk(relaxed = true)
        clockManager = LamportClockManager(initialClock = 100L, testDeviceId = "dev-upload")

        pipeline = MediaUploadPipeline(
            assetClient = assetClient,
            mediaAssetDao = db.mediaAssetDao(),
            mediaCryptoDao = db.mediaCryptoDao(),
            syncQueueDao = db.syncQueueDao(),
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
    fun uploadFromBytes_preUploadSha256Deduplication_skipsUploadWhenSynced() = runBlocking {
        val testBytes = "Sample Photo Plaintext Bytes for Deduplication".toByteArray(Charsets.UTF_8)
        val testSha256 = MediaHasher.computeSha256(testBytes)

        // Seed existing synced asset and crypto mapping
        db.mediaAssetDao().insert(
            MediaAssetEntity(
                fileId = "existing-file-123",
                mediaType = "image/jpeg",
                fileSize = testBytes.size.toLong(),
                capturedAt = 1000L
            )
        )
        db.mediaCryptoDao().insert(
            MediaCryptoMappingEntity(
                fileId = "existing-file-123",
                plaintextSha256 = testSha256,
                ciphertextHash = "cas-existing-hash",
                wrappedDek = "0".repeat(120),
                uploadStatus = UploadStatus.SYNCED
            )
        )

        // Attempt upload with identical bytes
        val result = pipeline.uploadFromBytes(
            bytes = testBytes,
            mediaType = "image/jpeg",
            masterVaultKey = masterVaultKey
        )

        assertTrue(result is UploadResult.Success)
        val success = result as UploadResult.Success
        assertTrue("Upload should be detected as deduplicated", success.isDeduplicated)
        assertEquals("existing-file-123", success.asset.fileId)

        // Server CAS upload must NOT have been called
        coVerify(exactly = 0) {
            assetClient.uploadBytes(any(), any(), any(), any())
        }
    }

    @Test
    fun uploadFromBytes_newAsset_encryptsUploadsPersistsAndQueuesAction() = runBlocking {
        val testBytes = "New Brand Fresh Photo Image Data".toByteArray(Charsets.UTF_8)
        val expectedSha256 = MediaHasher.computeSha256(testBytes)

        coEvery {
            assetClient.uploadBytes(any(), eq(AssetTag.GALLERY_META), any(), any())
        } returns NetworkResult.Success("blake3-cas-thumb-hash-888")
        coEvery {
            assetClient.uploadBytes(any(), eq(AssetTag.GALLERY_ITEM), any(), any())
        } returns NetworkResult.Success("blake3-cas-ciphertext-hash-999")

        val result = pipeline.uploadFromBytes(
            bytes = testBytes,
            mediaType = "image/jpeg",
            masterVaultKey = masterVaultKey,
            originalFileName = "my_photo.jpg"
        )

        assertTrue(result is UploadResult.Success)
        val success = result as UploadResult.Success
        assertFalse(success.isDeduplicated)
        val fileId = success.asset.fileId
        assertNotNull(fileId)

        // Verify media_assets persistence
        val persistedAsset = db.mediaAssetDao().getAssetById(fileId)
        assertNotNull(persistedAsset)
        assertEquals(fileId, persistedAsset?.fileId)
        assertEquals(testBytes.size.toLong(), persistedAsset?.fileSize)

        // Verify media_crypto_mapping persistence
        val persistedCrypto = db.mediaCryptoDao().getByFileId(fileId)
        assertNotNull(persistedCrypto)
        assertEquals(expectedSha256, persistedCrypto?.plaintextSha256)
        assertEquals("blake3-cas-ciphertext-hash-999", persistedCrypto?.ciphertextHash)
        assertEquals("blake3-cas-thumb-hash-888", persistedCrypto?.thumbnailCiphertextHash)
        assertEquals("${fileId}_thumb", persistedCrypto?.thumbnailFileId)
        assertEquals(120, persistedCrypto?.wrappedDek?.length)
        assertEquals(UploadStatus.SYNCED, persistedCrypto?.uploadStatus)

        // Verify wrapped DEK can be unwrapped with MasterVaultKey
        val unwrappedDek = AesGcmEnvelope.unwrapKey(persistedCrypto!!.wrappedDek, masterVaultKey.keyBytes)
        assertEquals(32, unwrappedDek.size)

        // Verify offline sync queue contains AssetCreate action
        val pendingQueue = db.syncQueueDao().getAllPending("gallery")
        assertEquals(1, pendingQueue.size)
        val queueItem = pendingQueue.first()
        assertEquals("AssetCreate", queueItem.actionType)
        assertEquals(fileId, queueItem.entityId)

        val decodedAction = protoBuf.decodeFromByteArray(CrdtAction.serializer(), queueItem.payloadProtobuf) as CrdtAction.AssetCreate
        assertEquals(fileId, decodedAction.fileId)
        assertEquals(expectedSha256, decodedAction.plaintextSha256)
        assertEquals("blake3-cas-ciphertext-hash-999", decodedAction.ciphertextHash)
        assertEquals("blake3-cas-thumb-hash-888", decodedAction.thumbnailCiphertextHash)
    }

    @Test
    fun uploadFromBytes_emptyBytes_returnsError() = runBlocking {
        val result = pipeline.uploadFromBytes(
            bytes = ByteArray(0),
            mediaType = "image/jpeg",
            masterVaultKey = masterVaultKey
        )
        assertTrue(result is UploadResult.Error)
        assertEquals("Cannot upload empty media bytes", (result as UploadResult.Error).message)
    }

    @Test
    fun uploadFromBytes_emitsLifecycleProgressStages() = runBlocking {
        val testBytes = "Progress Test Image".toByteArray()
        coEvery {
            assetClient.uploadBytes(any(), eq(AssetTag.GALLERY_META), any(), any())
        } returns NetworkResult.Success("thumb-hash-prog")
        coEvery {
            assetClient.uploadBytes(any(), eq(AssetTag.GALLERY_ITEM), any(), any())
        } answers {
            val prog = arg<((Long, Long) -> Unit)?>(3)
            prog?.invoke(50L, 100L)
            prog?.invoke(100L, 100L)
            NetworkResult.Success("item-hash-prog")
        }

        val stages = mutableListOf<project.kosh.core.model.UploadStage>()
        val result = pipeline.uploadFromBytes(
            bytes = testBytes,
            mediaType = "image/jpeg",
            masterVaultKey = masterVaultKey,
            onProgress = { stages.add(it) }
        )

        assertTrue(result is UploadResult.Success)
        assertTrue(stages.any { it is project.kosh.core.model.UploadStage.Hashing })
        assertTrue(stages.any { it is project.kosh.core.model.UploadStage.GeneratingThumbnail })
        assertTrue(stages.any { it is project.kosh.core.model.UploadStage.Encrypting })
        assertTrue(stages.any { it is project.kosh.core.model.UploadStage.Uploading })
        assertTrue(stages.any { it is project.kosh.core.model.UploadStage.Finalizing })
        assertTrue(stages.any { it is project.kosh.core.model.UploadStage.Completed })
    }

    @Test
    fun uploadFromBytes_withVideoAndCustomThumbnailBytes_encryptsAndUploadsThumbnail() = runBlocking {
        val testVideoBytes = "Fake MP4 video binary content".toByteArray()
        val testThumbBytes = "Fake JPEG video frame thumbnail".toByteArray()

        coEvery {
            assetClient.uploadBytes(any(), eq(AssetTag.GALLERY_META), any(), any())
        } returns NetworkResult.Success("cas-video-thumb-hash-111")
        coEvery {
            assetClient.uploadBytes(any(), eq(AssetTag.GALLERY_ITEM), any(), any())
        } returns NetworkResult.Success("cas-video-ciphertext-hash-222")

        val result = pipeline.uploadFromBytes(
            bytes = testVideoBytes,
            mediaType = "video/mp4",
            masterVaultKey = masterVaultKey,
            originalFileName = "sample_clip.mp4",
            customThumbnailBytes = testThumbBytes
        )

        assertTrue(result is UploadResult.Success)
        val fileId = (result as UploadResult.Success).asset.fileId

        val persistedCrypto = db.mediaCryptoDao().getByFileId(fileId)
        assertNotNull(persistedCrypto)
        assertEquals("cas-video-thumb-hash-111", persistedCrypto?.thumbnailCiphertextHash)
        assertEquals("${fileId}_thumb", persistedCrypto?.thumbnailFileId)

        coVerify(exactly = 1) {
            assetClient.uploadBytes(any(), eq(AssetTag.GALLERY_META), eq("${fileId}_thumb.bin"), any())
        }
        coVerify(exactly = 1) {
            assetClient.uploadBytes(any(), eq(AssetTag.GALLERY_ITEM), eq("sample_clip.mp4"), any())
        }
    }

    @Test
    fun uploadFromBytes_videoWithNullCustomThumbnail_handlesFallbackGracefully() = runBlocking {
        val testVideoBytes = "MP4 content without pre-generated thumbnail".toByteArray()
        coEvery {
            assetClient.uploadBytes(any(), eq(AssetTag.GALLERY_ITEM), any(), any())
        } returns NetworkResult.Success("cas-vid-item-hash")

        val result = pipeline.uploadFromBytes(
            bytes = testVideoBytes,
            mediaType = "video/mp4",
            masterVaultKey = masterVaultKey,
            customThumbnailBytes = null
        )

        assertTrue(result is UploadResult.Success)
        val fileId = (result as UploadResult.Success).asset.fileId
        val persistedAsset = db.mediaAssetDao().getAssetById(fileId)
        assertNotNull(persistedAsset)
        assertEquals("video/mp4", persistedAsset?.mediaType)
    }

    @Test
    fun uploadFromUri_video_readsBytesAndUploadsSuccessfully(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempFile = java.io.File.createTempFile("test_vid_upload_", ".mp4", context.cacheDir)
        tempFile.writeBytes("Video payload for uploadFromUri test".toByteArray())
        val uri = android.net.Uri.fromFile(tempFile)

        coEvery {
            assetClient.uploadBytes(any(), eq(AssetTag.GALLERY_ITEM), any(), any())
        } returns NetworkResult.Success("cas-uri-vid-hash")

        val result = pipeline.uploadFromUri(
            contentResolver = context.contentResolver,
            uri = uri,
            masterVaultKey = masterVaultKey,
            mediaType = "video/mp4",
            originalFileName = "test_vid.mp4"
        )

        assertTrue(result is UploadResult.Success)
        val fileId = (result as UploadResult.Success).asset.fileId
        val persistedCrypto = db.mediaCryptoDao().getByFileId(fileId)
        assertNotNull(persistedCrypto)
        assertEquals("cas-uri-vid-hash", persistedCrypto?.ciphertextHash)

        tempFile.delete()
    }
}

