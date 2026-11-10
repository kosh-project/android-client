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
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.repository.AlbumRepository
import project.kosh.core.database.repository.DefaultAlbumRepository

@RunWith(RobolectricTestRunner::class)
class AlbumRepositoryTest {

    private lateinit var db: KoshDatabase
    private lateinit var repository: AlbumRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KoshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultAlbumRepository(
            database = db,
            albumDao = db.albumDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertAsset(fileId: String) = runBlocking {
        db.mediaAssetDao().insert(
            MediaAssetEntity(
                fileId = fileId,
                mediaType = "image/jpeg",
                fileSize = 1024L,
                capturedAt = System.currentTimeMillis()
            )
        )
    }

    @Test
    fun createAlbum_andAddMedia_autoSetsCoverIfNone() = runBlocking {
        insertAsset("p1")
        insertAsset("p2")

        val album = repository.createAlbum(title = "Road Trip")
        assertNotNull(album.albumId)
        assertNull(album.coverFileId)

        // Add photos to album
        repository.addMediaToAlbum(album.albumId, listOf("p1", "p2"))

        // Auto sets cover to first photo added
        val updated = repository.getAlbumById(album.albumId)
        assertEquals("p1", updated?.coverFileId)

        val mediaInAlbum = repository.getMediaForAlbum(album.albumId)
        assertEquals(2, mediaInAlbum.size)
        assertEquals(listOf("p1", "p2"), mediaInAlbum.map { it.fileId })
    }

    @Test
    fun reorderAlbum_updatesPositionOrder() = runBlocking {
        insertAsset("p1")
        insertAsset("p2")
        insertAsset("p3")

        val album = repository.createAlbum(title = "Chronological Album")
        repository.addMediaToAlbum(album.albumId, listOf("p1", "p2", "p3"))

        repository.reorderAlbum(album.albumId, listOf("p3", "p2", "p1"))

        val reordered = repository.getMediaForAlbum(album.albumId)
        assertEquals(listOf("p3", "p2", "p1"), reordered.map { it.fileId })
    }

    @Test
    fun removeMediaFromAlbum_removesItems() = runBlocking {
        insertAsset("p1")
        insertAsset("p2")

        val album = repository.createAlbum(title = "Temp Album")
        repository.addMediaToAlbum(album.albumId, listOf("p1", "p2"))

        repository.removeMediaFromAlbum(album.albumId, listOf("p1"))
        val remaining = repository.getMediaForAlbum(album.albumId)
        assertEquals(1, remaining.size)
        assertEquals("p2", remaining.first().fileId)

        repository.clearAlbum(album.albumId)
        assertEquals(0, repository.getMediaForAlbum(album.albumId).size)
    }

    @Test
    fun deleteAlbum_deletesAlbumRecord() = runBlocking {
        val album = repository.createAlbum(title = "To be deleted")
        assertNotNull(repository.getAlbumById(album.albumId))

        val deleted = repository.deleteAlbum(album.albumId)
        assertTrue(deleted)
        assertNull(repository.getAlbumById(album.albumId))
    }

    @Test
    fun getAlbumWithMediaData_resolvesCoverAndMediaItems() = runBlocking {
        insertAsset("cover-photo")
        insertAsset("photo-2")

        val album = repository.createAlbum(
            title = "Family",
            coverFileId = "cover-photo"
        )
        repository.addMediaToAlbum(album.albumId, listOf("cover-photo", "photo-2"))

        val fullData = repository.getAlbumWithMediaData(album.albumId)
        assertNotNull(fullData)
        assertEquals("Family", fullData?.album?.title)
        assertEquals("cover-photo", fullData?.coverAsset?.fileId)
        assertEquals(2, fullData?.mediaAssets?.size)
    }

    @Test
    fun getAlbumWithMediaData_preservesCustomPositionOrdering() = runBlocking {
        insertAsset("p1")
        insertAsset("p2")
        insertAsset("p3")

        val album = repository.createAlbum(title = "Ordered Album")
        repository.addMediaToAlbum(album.albumId, listOf("p1", "p2", "p3"))

        // Reorder album: p3, p1, p2
        repository.reorderAlbum(album.albumId, listOf("p3", "p1", "p2"))

        val albumData = repository.getAlbumWithMediaData(album.albumId)
        assertNotNull(albumData)
        assertEquals(listOf("p3", "p1", "p2"), albumData?.mediaAssets?.map { it.fileId })
    }

    @Test
    fun observeAlbumWithMediaData_emitsOrderedUpdatesLive() = runBlocking {
        insertAsset("p1")
        insertAsset("p2")

        val album = repository.createAlbum(title = "Reactive Album")
        repository.addMediaToAlbum(album.albumId, listOf("p1", "p2"))

        val initial = repository.observeAlbumWithMediaData(album.albumId).first()
        assertNotNull(initial)
        assertEquals(listOf("p1", "p2"), initial?.mediaAssets?.map { it.fileId })

        // Reverse order
        repository.reorderAlbum(album.albumId, listOf("p2", "p1"))

        val updated = repository.observeAlbumWithMediaData(album.albumId).first()
        assertEquals(listOf("p2", "p1"), updated?.mediaAssets?.map { it.fileId })
    }

    @Test
    fun updateAlbumDetails_updatesDetailsViaRepository() = runBlocking {
        val album = repository.createAlbum(title = "Original Title", description = "Original Desc")
        repository.updateAlbumDetails(album.albumId, "Updated Title", "Updated Desc")

        val updated = repository.getAlbumById(album.albumId)
        assertEquals("Updated Title", updated?.title)
        assertEquals("Updated Desc", updated?.description)
    }

    @Test
    fun clearAllAlbums_wipesAllAlbums() = runBlocking {
        repository.createAlbum(title = "Album 1")
        repository.createAlbum(title = "Album 2")
        assertEquals(2, repository.getAllAlbums().size)

        val clearedCount = repository.clearAllAlbums()
        assertEquals(2, clearedCount)
        assertEquals(0, repository.getAllAlbums().size)
    }
}

