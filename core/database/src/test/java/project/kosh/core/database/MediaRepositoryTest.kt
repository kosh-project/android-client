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
import project.kosh.core.database.repository.DefaultMediaRepository
import project.kosh.core.database.repository.MediaRepository
import project.kosh.core.model.ExifMetadata
import project.kosh.core.model.MediaAsset
import project.kosh.core.model.MediaCryptoMapping
import project.kosh.core.model.UploadStatus

@RunWith(RobolectricTestRunner::class)
class MediaRepositoryTest {

    private lateinit var db: KoshDatabase
    private lateinit var repository: MediaRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KoshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultMediaRepository(
            database = db,
            mediaAssetDao = db.mediaAssetDao(),
            mediaCryptoDao = db.mediaCryptoDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleAsset(fileId: String = "f1", capturedAt: Long = 1000L): MediaAsset = MediaAsset(
        fileId = fileId,
        thumbnailHash = "thumb-$fileId",
        mediaType = "image/jpeg",
        width = 1920,
        height = 1080,
        fileSize = 512000L,
        exif = ExifMetadata(
            deviceMake = "Sony",
            deviceModel = "A7 IV",
            lensModel = "FE 24-70mm F2.8 GM II",
            aperture = 2.8,
            iso = 200
        ),
        capturedAt = capturedAt
    )

    private fun sampleCrypto(fileId: String = "f1"): MediaCryptoMapping = MediaCryptoMapping(
        fileId = fileId,
        plaintextHash = "plain-$fileId",
        wrappedDek = "0".repeat(120),
        uploadStatus = UploadStatus.PENDING
    )

    @Test
    fun saveMediaWithCrypto_persistsBothEntitiesAtomically() = runBlocking {
        val asset = sampleAsset("f1")
        val crypto = sampleCrypto("f1")

        repository.saveMediaWithCrypto(asset, crypto)

        val retrievedAsset = repository.getMediaById("f1")
        assertNotNull(retrievedAsset)
        assertEquals("Sony", retrievedAsset?.exif?.deviceMake)
        assertEquals("A7 IV", retrievedAsset?.exif?.deviceModel)
        assertEquals(2.8, retrievedAsset?.exif?.aperture!!, 0.001)

        val retrievedCrypto = repository.getCryptoMapping("f1")
        assertNotNull(retrievedCrypto)
        assertEquals("plain-f1", retrievedCrypto?.plaintextHash)
        assertEquals(UploadStatus.PENDING, retrievedCrypto?.uploadStatus)
    }

    @Test
    fun updateUploadStatus_updatesStateCorrectly() = runBlocking {
        repository.saveMediaWithCrypto(sampleAsset("f1"), sampleCrypto("f1"))

        val now = System.currentTimeMillis()
        val success = repository.updateUploadStatus(
            fileId = "f1",
            status = UploadStatus.SYNCED,
            ciphertextHash = "server-cipher-hash",
            thumbnailCiphertextHash = "server-thumb-hash",
            lastSyncedAt = now
        )
        assertTrue(success)

        val updated = repository.getCryptoMapping("f1")
        assertNotNull(updated)
        assertEquals(UploadStatus.SYNCED, updated?.uploadStatus)
        assertEquals("server-cipher-hash", updated?.ciphertextHash)
        assertEquals("server-thumb-hash", updated?.thumbnailCiphertextHash)
        assertEquals(now, updated?.lastSyncedAt)
    }

    @Test
    fun deleteMedia_removesAssetAndCrypto() = runBlocking {
        repository.saveMediaWithCrypto(sampleAsset("f1"), sampleCrypto("f1"))
        assertNotNull(repository.getMediaById("f1"))

        val deleted = repository.deleteMedia("f1")
        assertTrue(deleted)

        assertNull(repository.getMediaById("f1"))
        assertNull(repository.getCryptoMapping("f1"))
    }

    @Test
    fun batchSave_andCount_operations() = runBlocking {
        val assets = listOf(sampleAsset("f1", 1000L), sampleAsset("f2", 2000L))
        val cryptos = listOf(sampleCrypto("f1"), sampleCrypto("f2"))

        repository.saveMediaBatch(assets, cryptos)

        assertEquals(2, repository.getMediaCount())
        assertEquals(2, repository.observeMediaCount().first())

        val paged = repository.getMediaPaged(limit = 1, offset = 0)
        assertEquals(1, paged.size)
        assertEquals("f2", paged.first().fileId) // higher capturedAt first
    }

    @Test(expected = IllegalArgumentException::class)
    fun saveMediaBatch_withMismatchedSizes_throwsIllegalArgumentException() = runBlocking {
        val assets = listOf(sampleAsset("f1"))
        val cryptos = listOf(sampleCrypto("f1"), sampleCrypto("f2"))
        repository.saveMediaBatch(assets, cryptos)
    }

    @Test
    fun clearAllMedia_wipesBothAssetsAndCryptos() = runBlocking {
        repository.saveMediaWithCrypto(sampleAsset("f1"), sampleCrypto("f1"))
        repository.saveMediaWithCrypto(sampleAsset("f2"), sampleCrypto("f2"))
        assertEquals(2, repository.getMediaCount())

        val cleared = repository.clearAllMedia()
        assertEquals(2, cleared)
        assertEquals(0, repository.getMediaCount())
        assertNull(repository.getCryptoMapping("f1"))
        assertNull(repository.getCryptoMapping("f2"))
    }

    @Test
    fun resetStuckUploads_recoversStuckItems() = runBlocking {
        repository.saveMediaWithCrypto(
            sampleAsset("f1"),
            sampleCrypto("f1").copy(uploadStatus = UploadStatus.UPLOADING)
        )
        val reset = repository.resetStuckUploads()
        assertEquals(1, reset)

        val retrieved = repository.getCryptoMapping("f1")
        assertEquals(UploadStatus.PENDING, retrieved?.uploadStatus)
    }

    @Test
    fun getMediaBeforeCursor_withNullableCursor_pagesCorrectly() = runBlocking {
        repository.saveMediaBatch(
            listOf(sampleAsset("f1", 1000L), sampleAsset("f2", 2000L)),
            listOf(sampleCrypto("f1"), sampleCrypto("f2"))
        )

        // Page 1 with null cursor
        val page1 = repository.getMediaBeforeCursor(beforeCapturedAt = null, beforeFileId = null, limit = 1)
        assertEquals(1, page1.size)
        assertEquals("f2", page1.first().fileId)

        // Page 2
        val page2 = repository.getMediaBeforeCursor(
            beforeCapturedAt = page1.first().capturedAt,
            beforeFileId = page1.first().fileId,
            limit = 1
        )
        assertEquals(1, page2.size)
        assertEquals("f1", page2.first().fileId)
    }
}
