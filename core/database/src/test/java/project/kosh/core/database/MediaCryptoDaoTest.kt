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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.dao.MediaCryptoDao
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.model.UploadStatus

@RunWith(RobolectricTestRunner::class)
class MediaCryptoDaoTest {

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

    private fun insertParentAsset(fileId: String) = runBlocking {
        assetDao.insert(
            MediaAssetEntity(
                fileId = fileId,
                mediaType = "image/jpeg",
                fileSize = 1024L,
                capturedAt = System.currentTimeMillis()
            )
        )
    }

    @Test
    fun upsertAndGetCryptoMapping_persists60ByteWrappedDek() = runBlocking {
        insertParentAsset("f1")

        val wrappedDek120Hex = "0123456789abcdef".repeat(7) + "01234567" // 16*7 + 8 = 120 chars = 60 raw bytes
        assertEquals(120, wrappedDek120Hex.length)

        val crypto = MediaCryptoMappingEntity(
            fileId = "f1",
            plaintextSha256 = "blake3-plain-hash-001",
            ciphertextHash = null,
            thumbnailCiphertextHash = null,
            wrappedDek = wrappedDek120Hex,
            wrappedThumbnailDek = null,
            uploadStatus = UploadStatus.PENDING
        )

        cryptoDao.upsert(crypto)

        val retrieved = cryptoDao.getByFileId("f1")
        assertNotNull(retrieved)
        assertEquals("f1", retrieved?.fileId)
        assertEquals("blake3-plain-hash-001", retrieved?.plaintextHash)
        assertNull(retrieved?.ciphertextHash)
        assertEquals(120, retrieved?.wrappedDek?.length)
        assertEquals(UploadStatus.PENDING, retrieved?.uploadStatus)
    }

    @Test
    fun findByPlaintextHash_findsMatchingPreUploadAsset() = runBlocking {
        insertParentAsset("f1")
        val crypto = MediaCryptoMappingEntity(
            fileId = "f1",
            plaintextSha256 = "unique-plaintext-hash",
            wrappedDek = "0".repeat(120),
            uploadStatus = UploadStatus.PENDING
        )
        cryptoDao.insert(crypto)

        val found = cryptoDao.getByPlaintextHash("unique-plaintext-hash")
        assertNotNull(found)
        assertEquals("f1", found?.fileId)

        val notFound = cryptoDao.getByPlaintextHash("non-existent-hash")
        assertNull(notFound)
    }

    @Test
    fun findByCiphertextHash_findsUploadedAsset() = runBlocking {
        insertParentAsset("f1")
        val crypto = MediaCryptoMappingEntity(
            fileId = "f1",
            plaintextSha256 = "plain-1",
            ciphertextHash = "cas-blake3-hash-commit-1",
            wrappedDek = "0".repeat(120),
            uploadStatus = UploadStatus.SYNCED
        )
        cryptoDao.insert(crypto)

        val found = cryptoDao.getByCiphertextHash("cas-blake3-hash-commit-1")
        assertNotNull(found)
        assertEquals("f1", found?.fileId)
    }

    @Test
    fun getPendingOrFailedUploads_filtersAccurately() = runBlocking {
        insertParentAsset("f1")
        insertParentAsset("f2")
        insertParentAsset("f3")

        cryptoDao.insert(MediaCryptoMappingEntity("f1", "p1", null, null, "0".repeat(120), null, UploadStatus.PENDING))
        cryptoDao.insert(MediaCryptoMappingEntity("f2", "p2", "c2", null, "0".repeat(120), null, UploadStatus.SYNCED))
        cryptoDao.insert(MediaCryptoMappingEntity("f3", "p3", null, null, "0".repeat(120), null, UploadStatus.FAILED, errorMessage = "Socket timeout"))

        val queue = cryptoDao.getPendingOrFailedUploads()
        assertEquals(2, queue.size)
        val fileIds = queue.map { it.fileId }.toSet()
        assertEquals(setOf("f1", "f3"), fileIds)

        val observableQueue = cryptoDao.observePendingOrFailedUploads().first()
        assertEquals(2, observableQueue.size)
    }

    @Test
    fun updateUploadStatus_transitionsStateAtomically() = runBlocking {
        insertParentAsset("f1")
        cryptoDao.insert(MediaCryptoMappingEntity("f1", "p1", null, null, "0".repeat(120), null, UploadStatus.PENDING))

        val now = System.currentTimeMillis()
        val rowsUpdated = cryptoDao.updateUploadStatus(
            fileId = "f1",
            status = UploadStatus.SYNCED,
            ciphertextHash = "server-cas-commit-hash",
            thumbnailCiphertextHash = "server-thumb-cas-hash",
            lastSyncedAt = now,
            errorMessage = null
        )

        assertEquals(1, rowsUpdated)

        val updated = cryptoDao.getByFileId("f1")
        assertNotNull(updated)
        assertEquals(UploadStatus.SYNCED, updated?.uploadStatus)
        assertEquals("server-cas-commit-hash", updated?.ciphertextHash)
        assertEquals("server-thumb-cas-hash", updated?.thumbnailCiphertextHash)
        assertEquals(now, updated?.lastSyncedAt)
        assertNull(updated?.errorMessage)
    }

    @Test
    fun updateUploadStatus_withFailure_preservesLastSyncedAt() = runBlocking {
        insertParentAsset("f1")
        val initialSyncedTime = 1710000000000L
        cryptoDao.insert(
            MediaCryptoMappingEntity(
                fileId = "f1",
                plaintextSha256 = "p1",
                ciphertextHash = "c1",
                wrappedDek = "0".repeat(120),
                uploadStatus = UploadStatus.SYNCED,
                lastSyncedAt = initialSyncedTime
            )
        )

        // Now an upload retry fails
        cryptoDao.updateUploadStatus(
            fileId = "f1",
            status = UploadStatus.FAILED,
            lastSyncedAt = null,
            errorMessage = "Connection reset by peer"
        )

        val updated = cryptoDao.getByFileId("f1")
        assertNotNull(updated)
        assertEquals(UploadStatus.FAILED, updated?.uploadStatus)
        assertEquals("Connection reset by peer", updated?.errorMessage)
        // Critical: lastSyncedAt must NOT be wiped to null via COALESCE!
        assertEquals(initialSyncedTime, updated?.lastSyncedAt)
    }

    @Test
    fun resetStuckUploads_recoversStuckUploadingToPending() = runBlocking {
        insertParentAsset("f1")
        insertParentAsset("f2")
        cryptoDao.insert(MediaCryptoMappingEntity("f1", "p1", null, null, "0".repeat(120), null, UploadStatus.UPLOADING))
        cryptoDao.insert(MediaCryptoMappingEntity("f2", "p2", "c2", null, "0".repeat(120), null, UploadStatus.SYNCED))

        val resetCount = cryptoDao.resetStuckUploads()
        assertEquals(1, resetCount)

        val recovered = cryptoDao.getByFileId("f1")
        assertEquals(UploadStatus.PENDING, recovered?.uploadStatus)

        val synced = cryptoDao.getByFileId("f2")
        assertEquals(UploadStatus.SYNCED, synced?.uploadStatus)
    }

    @Test
    fun getUnsyncedUploads_returnsAllNonSyncedAssets() = runBlocking {
        insertParentAsset("f1")
        insertParentAsset("f2")
        insertParentAsset("f3")
        cryptoDao.insert(MediaCryptoMappingEntity("f1", "p1", null, null, "0".repeat(120), null, UploadStatus.PENDING))
        cryptoDao.insert(MediaCryptoMappingEntity("f2", "p2", null, null, "0".repeat(120), null, UploadStatus.UPLOADING))
        cryptoDao.insert(MediaCryptoMappingEntity("f3", "p3", "c3", null, "0".repeat(120), null, UploadStatus.SYNCED))

        val unsynced = cryptoDao.getUnsyncedUploads()
        assertEquals(2, unsynced.size)
        assertEquals(setOf("f1", "f2"), unsynced.map { it.fileId }.toSet())
    }
}
