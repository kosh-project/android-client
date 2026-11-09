package project.kosh.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.dao.MediaCryptoDao
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.model.AssetTag
import project.kosh.core.model.UploadStatus

@RunWith(RobolectricTestRunner::class)
class MediaAssetDaoTest {

    private lateinit var db: KoshDatabase
    private lateinit var assetDao: MediaAssetDao
    private lateinit var cryptoDao: MediaCryptoDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KoshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        assetDao = db.mediaAssetDao()
        cryptoDao = db.mediaCryptoDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun createTestAsset(
        fileId: String = "file-uuid-001",
        capturedAt: Long = 1700000000000L,
        mediaType: String = "image/jpeg",
        size: Long = 4096123L
    ): MediaAssetEntity = MediaAssetEntity(
        fileId = fileId,
        thumbnailHash = "thumb-hash-blake3-abc123",
        mediaType = mediaType,
        width = 4032,
        height = 3024,
        durationMs = null,
        fileSize = size,
        deviceMake = "Google",
        deviceModel = "Pixel 8 Pro",
        lensModel = "Google Pixel 8 Pro rear camera 6.9mm f/1.68",
        focalLength = 6.9,
        aperture = 1.68,
        iso = 100,
        exposureTime = 0.005,
        latitude = 37.7749,
        longitude = -122.4194,
        altitude = 15.2,
        capturedAt = capturedAt,
        createdAt = capturedAt + 1000,
        updatedAt = capturedAt + 1000,
        assetTag = AssetTag.GALLERY_ITEM
    )

    @Test
    fun insertAndGetAssetById_persistsCompleteExifAndGeometry() = runBlocking {
        val asset = createTestAsset()
        assetDao.insert(asset)

        val retrieved = assetDao.getAssetById(asset.fileId)
        assertNotNull(retrieved)
        val nonNullRetrieved = retrieved!!
        assertEquals("file-uuid-001", nonNullRetrieved.fileId)
        assertEquals("thumb-hash-blake3-abc123", nonNullRetrieved.thumbnailHash)
        assertEquals("image/jpeg", nonNullRetrieved.mediaType)
        assertEquals(4032, nonNullRetrieved.width)
        assertEquals(3024, nonNullRetrieved.height)
        assertNull(nonNullRetrieved.durationMs)
        assertEquals(4096123L, nonNullRetrieved.fileSize)
        assertEquals("Google", nonNullRetrieved.deviceMake)
        assertEquals("Pixel 8 Pro", nonNullRetrieved.deviceModel)
        assertEquals(1.68, nonNullRetrieved.aperture!!, 0.001)
        assertEquals(6.9, nonNullRetrieved.focalLength!!, 0.001)
        assertEquals(100, nonNullRetrieved.iso)
        assertEquals(0.005, nonNullRetrieved.exposureTime!!, 0.0001)
        assertEquals(37.7749, nonNullRetrieved.latitude!!, 0.0001)
        assertEquals(-122.4194, nonNullRetrieved.longitude!!, 0.0001)
        assertEquals(15.2, nonNullRetrieved.altitude!!, 0.001)
        assertEquals(1700000000000L, nonNullRetrieved.capturedAt)
        assertEquals(AssetTag.GALLERY_ITEM, nonNullRetrieved.assetTag)
    }

    @Test
    fun observeAssetById_emitsLiveUpdates() = runBlocking {
        val asset = createTestAsset()
        assetDao.insert(asset)

        val initial = assetDao.observeAssetById(asset.fileId).first()
        assertEquals(asset.fileId, initial?.fileId)

        val updated = asset.copy(deviceModel = "Pixel 9 Pro")
        assetDao.update(updated)

        val reEmitted = assetDao.observeAssetById(asset.fileId).first()
        assertEquals("Pixel 9 Pro", reEmitted?.deviceModel)
    }

    @Test
    fun keysetPagination_ordersByCapturedAtDesc() = runBlocking {
        val a1 = createTestAsset("f1", capturedAt = 1000L)
        val a2 = createTestAsset("f2", capturedAt = 2000L)
        val a3 = createTestAsset("f3", capturedAt = 3000L)
        val a4 = createTestAsset("f4", capturedAt = 4000L)

        assetDao.upsertAll(listOf(a1, a2, a3, a4))

        // Get assets captured before 3500 (should return a3 then a2)
        val page = assetDao.getAssetsCapturedBefore(beforeCapturedAt = 3500L, limit = 2)
        assertEquals(2, page.size)
        assertEquals("f3", page[0].fileId)
        assertEquals("f2", page[1].fileId)

        // Cursor pagination tie breaker
        val cursorPage = assetDao.getAssetsBeforeCursor(beforeCapturedAt = 3000L, beforeFileId = "f3", limit = 10)
        assertEquals(2, cursorPage.size)
        assertEquals("f2", cursorPage[0].fileId)
        assertEquals("f1", cursorPage[1].fileId)
    }

    @Test
    fun observeAssetsByType_filtersMimePrefixCorrectly() = runBlocking {
        val photo = createTestAsset("img1", mediaType = "image/jpeg")
        val video = createTestAsset("vid1", mediaType = "video/mp4")

        assetDao.insertAll(listOf(photo, video))

        val images = assetDao.observeAssetsByType("image/").first()
        assertEquals(1, images.size)
        assertEquals("img1", images.first().fileId)

        val videos = assetDao.observeAssetsByType("video/").first()
        assertEquals(1, videos.size)
        assertEquals("vid1", videos.first().fileId)
    }

    @Test
    fun deleteAsset_cascadesToCryptoMapping() = runBlocking {
        val asset = createTestAsset("f1")
        assetDao.insert(asset)

        val crypto = MediaCryptoMappingEntity(
            fileId = "f1",
            plaintextHash = "plain-hash-001",
            ciphertextHash = "cipher-hash-001",
            wrappedDek = "a".repeat(120),
            uploadStatus = UploadStatus.SYNCED
        )
        cryptoDao.insert(crypto)

        // Verify both exist
        assertNotNull(assetDao.getAssetById("f1"))
        assertNotNull(cryptoDao.getByFileId("f1"))

        // Delete parent asset
        val deletedCount = assetDao.deleteById("f1")
        assertEquals(1, deletedCount)

        // Asset deleted
        assertNull(assetDao.getAssetById("f1"))
        // Foreign key CASCADE deletes child crypto mapping in SQLite!
        assertNull(cryptoDao.getByFileId("f1"))
    }

    @Test
    fun getMediaWithCryptoById_resolves1to1Relationship() = runBlocking {
        val asset = createTestAsset("f1")
        assetDao.insert(asset)

        val crypto = MediaCryptoMappingEntity(
            fileId = "f1",
            plaintextHash = "plain-hash-001",
            ciphertextHash = "cipher-hash-001",
            wrappedDek = "f".repeat(120),
            uploadStatus = UploadStatus.SYNCED
        )
        cryptoDao.insert(crypto)

        val combined = assetDao.getMediaWithCryptoById("f1")
        assertNotNull(combined)
        assertEquals("f1", combined?.asset?.fileId)
        assertEquals("cipher-hash-001", combined?.cryptoMapping?.ciphertextHash)
        assertEquals("f".repeat(120), combined?.cryptoMapping?.wrappedDek)
    }

    @Test
    fun assetCount_accuratelyReflectsDatabaseState() = runBlocking {
        assertEquals(0, assetDao.getAssetCount())
        assetDao.insert(createTestAsset("f1"))
        assetDao.insert(createTestAsset("f2"))
        assertEquals(2, assetDao.getAssetCount())
        assertEquals(2, assetDao.observeAssetCount().first())
    }

    @Test
    fun getAssetsBeforeCursor_deterministicTieBreakerWithIdenticalTimestamps() = runBlocking {
        // Stress test: 5 items with identical millisecond captured_at timestamp
        val sameTime = 1700000000000L
        val items = listOf("uuid-1", "uuid-2", "uuid-3", "uuid-4", "uuid-5").map { id ->
            createTestAsset(fileId = id, capturedAt = sameTime)
        }
        assetDao.upsertAll(items)

        // Page 1 with limit 2: should return uuid-5, uuid-4
        val page1 = assetDao.getAssetsBeforeCursor(beforeCapturedAt = null, beforeFileId = null, limit = 2)
        assertEquals(2, page1.size)
        assertEquals(listOf("uuid-5", "uuid-4"), page1.map { it.fileId })

        // Page 2 using cursor from page 1 last item
        val lastItem1 = page1.last()
        val page2 = assetDao.getAssetsBeforeCursor(
            beforeCapturedAt = lastItem1.capturedAt,
            beforeFileId = lastItem1.fileId,
            limit = 2
        )
        assertEquals(2, page2.size)
        assertEquals(listOf("uuid-3", "uuid-2"), page2.map { it.fileId })

        // Page 3
        val lastItem2 = page2.last()
        val page3 = assetDao.getAssetsBeforeCursor(
            beforeCapturedAt = lastItem2.capturedAt,
            beforeFileId = lastItem2.fileId,
            limit = 2
        )
        assertEquals(1, page3.size)
        assertEquals(listOf("uuid-1"), page3.map { it.fileId })

        // Page 4 should be empty
        val lastItem3 = page3.last()
        val page4 = assetDao.getAssetsBeforeCursor(
            beforeCapturedAt = lastItem3.capturedAt,
            beforeFileId = lastItem3.fileId,
            limit = 2
        )
        assertTrue(page4.isEmpty())
    }

    @Test
    fun getAssetsCapturedBetween_filtersRangeCorrectly() = runBlocking {
        val a1 = createTestAsset("f1", capturedAt = 1000L)
        val a2 = createTestAsset("f2", capturedAt = 2000L)
        val a3 = createTestAsset("f3", capturedAt = 3000L)
        val a4 = createTestAsset("f4", capturedAt = 4000L)
        assetDao.upsertAll(listOf(a1, a2, a3, a4))

        val inRange = assetDao.getAssetsCapturedBetween(1500L, 3500L)
        assertEquals(2, inRange.size)
        assertEquals(listOf("f3", "f2"), inRange.map { it.fileId })
    }

    @Test
    fun updateThumbnailHash_persistsUpdatedHash() = runBlocking {
        val asset = createTestAsset("f1")
        assetDao.insert(asset)

        val updatedRows = assetDao.updateThumbnailHash("f1", "new-blake3-thumbnail-hash")
        assertEquals(1, updatedRows)

        val retrieved = assetDao.getAssetById("f1")
        assertEquals("new-blake3-thumbnail-hash", retrieved?.thumbnailHash)
    }
}
