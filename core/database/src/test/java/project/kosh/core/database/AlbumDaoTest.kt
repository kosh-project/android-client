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
import project.kosh.core.database.dao.AlbumDao
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.entity.AlbumMediaCrossRef
import project.kosh.core.database.entity.MediaAssetEntity

@RunWith(RobolectricTestRunner::class)
class AlbumDaoTest {

    private lateinit var db: KoshDatabase
    private lateinit var albumDao: AlbumDao
    private lateinit var assetDao: MediaAssetDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KoshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        albumDao = db.albumDao()
        assetDao = db.mediaAssetDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun insertAsset(fileId: String, capturedAt: Long = 1000L) = runBlocking {
        assetDao.insert(
            MediaAssetEntity(
                fileId = fileId,
                mediaType = "image/jpeg",
                fileSize = 2048L,
                capturedAt = capturedAt
            )
        )
    }

    @Test
    fun insertAndGetAlbumById_persistsMetadata() = runBlocking {
        insertAsset("cover-01")

        val album = AlbumEntity(
            albumId = "album-01",
            title = "Vacation in Iceland",
            description = "Northern lights and glaciers",
            coverFileId = "cover-01"
        )
        albumDao.insert(album)

        val retrieved = albumDao.getAlbumById("album-01")
        assertNotNull(retrieved)
        assertEquals("album-01", retrieved?.albumId)
        assertEquals("Vacation in Iceland", retrieved?.title)
        assertEquals("Northern lights and glaciers", retrieved?.description)
        assertEquals("cover-01", retrieved?.coverFileId)
    }

    @Test
    fun deleteCoverAsset_setsCoverFileIdToNull_preservesAlbum() = runBlocking {
        insertAsset("cover-01")
        val album = AlbumEntity(
            albumId = "album-01",
            title = "Summer Trip",
            coverFileId = "cover-01"
        )
        albumDao.insert(album)

        // Verify album has cover
        val initialAlbum = albumDao.getAlbumById("album-01")
        assertEquals("cover-01", initialAlbum?.coverFileId)

        // Delete the cover photo from vault
        assetDao.deleteById("cover-01")
        assertNull(assetDao.getAssetById("cover-01"))

        // Album must still exist, with coverFileId set to null via ForeignKey.SET_NULL!
        val albumAfterCoverDeleted = albumDao.getAlbumById("album-01")
        assertNotNull(albumAfterCoverDeleted)
        assertNull(albumAfterCoverDeleted?.coverFileId)
        assertEquals("Summer Trip", albumAfterCoverDeleted?.title)
    }

    @Test
    fun addMediaToAlbum_andQueryManytoManyRelation() = runBlocking {
        insertAsset("p1", 1000L)
        insertAsset("p2", 2000L)
        insertAsset("p3", 3000L)

        albumDao.insert(AlbumEntity(albumId = "alb1", title = "Nature"))

        albumDao.addMediaToAlbum("alb1", listOf("p1", "p2", "p3"))

        val albumWithMedia = albumDao.getAlbumWithMedia("alb1")
        assertNotNull(albumWithMedia)
        assertEquals("alb1", albumWithMedia?.album?.albumId)
        assertEquals(3, albumWithMedia?.mediaAssets?.size)

        val mediaCount = albumDao.getMediaCountInAlbum("alb1")
        assertEquals(3, mediaCount)
    }

    @Test
    fun customPositionOrdering_andReordering_preservesExactOrder() = runBlocking {
        insertAsset("p1")
        insertAsset("p2")
        insertAsset("p3")

        albumDao.insert(AlbumEntity(albumId = "alb1", title = "Reorder Test"))

        // Insert in order: p1 (0), p2 (1), p3 (2)
        albumDao.addMediaToAlbum("alb1", listOf("p1", "p2", "p3"))

        var orderedMedia = albumDao.getMediaForAlbumOrdered("alb1")
        assertEquals(listOf("p1", "p2", "p3"), orderedMedia.map { it.fileId })

        // Now reverse order: p3 (0), p1 (1), p2 (2)
        albumDao.reorderAlbumMedia("alb1", listOf("p3", "p1", "p2"))

        orderedMedia = albumDao.getMediaForAlbumOrdered("alb1")
        assertEquals(listOf("p3", "p1", "p2"), orderedMedia.map { it.fileId })
    }

    @Test
    fun deleteAlbum_cascadesCrossRefs_leavesMediaAssetsIntact() = runBlocking {
        insertAsset("p1")
        insertAsset("p2")

        albumDao.insert(AlbumEntity(albumId = "alb1", title = "Temporary Album"))
        albumDao.addMediaToAlbum("alb1", listOf("p1", "p2"))

        assertEquals(2, albumDao.getCrossRefsForAlbum("alb1").size)

        // Delete the album
        albumDao.deleteById("alb1")

        // Cross refs are cascaded away
        assertEquals(0, albumDao.getCrossRefsForAlbum("alb1").size)

        // Original media assets remain safe in the library!
        assertNotNull(assetDao.getAssetById("p1"))
        assertNotNull(assetDao.getAssetById("p2"))
    }

    @Test
    fun deleteMediaAsset_cascadesFromAllAlbums() = runBlocking {
        insertAsset("shared-photo")
        albumDao.insert(AlbumEntity(albumId = "a1", title = "Album 1"))
        albumDao.insert(AlbumEntity(albumId = "a2", title = "Album 2"))

        albumDao.addMediaToAlbum("a1", listOf("shared-photo"))
        albumDao.addMediaToAlbum("a2", listOf("shared-photo"))

        assertEquals(1, albumDao.getMediaCountInAlbum("a1"))
        assertEquals(1, albumDao.getMediaCountInAlbum("a2"))

        // Delete the media asset from vault
        assetDao.deleteById("shared-photo")

        // Both albums automatically drop the reference
        assertEquals(0, albumDao.getMediaCountInAlbum("a1"))
        assertEquals(0, albumDao.getMediaCountInAlbum("a2"))
    }

    @Test
    fun removeMediaFromAlbum_removesSpecifiedPhotos() = runBlocking {
        insertAsset("p1")
        insertAsset("p2")
        insertAsset("p3")

        albumDao.insert(AlbumEntity(albumId = "alb1", title = "Editing Album"))
        albumDao.addMediaToAlbum("alb1", listOf("p1", "p2", "p3"))

        // Remove p2
        val removed = albumDao.removeMediaFromAlbum("alb1", "p2")
        assertEquals(1, removed)

        val remaining = albumDao.getMediaForAlbumOrdered("alb1")
        assertEquals(listOf("p1", "p3"), remaining.map { it.fileId })

        // Clear remaining
        albumDao.clearAlbum("alb1")
        assertEquals(0, albumDao.getMediaCountInAlbum("alb1"))
    }

    @Test
    fun observeAlbumsWithCover_resolvesCoverEntity() = runBlocking {
        insertAsset("cover-img")
        albumDao.insert(AlbumEntity("alb1", "Album with cover", coverFileId = "cover-img"))

        val albumsWithCover = albumDao.observeAlbumsWithCover().first()
        assertEquals(1, albumsWithCover.size)
        assertEquals("alb1", albumsWithCover.first().album.albumId)
        assertEquals("cover-img", albumsWithCover.first().coverAsset?.fileId)
    }

    @Test
    fun removeCoverAssetFromAlbum_fallsBackToNextMediaInAlbum() = runBlocking {
        insertAsset("p1")
        insertAsset("p2")
        albumDao.insert(AlbumEntity("alb1", "Cover Fallback Test"))
        albumDao.addMediaToAlbum("alb1", listOf("p1", "p2"))

        // p1 became the cover
        assertEquals("p1", albumDao.getAlbumById("alb1")?.coverFileId)

        // Remove p1 from album
        val removed = albumDao.removeMediaFromAlbum("alb1", "p1")
        assertEquals(1, removed)

        // Cover must automatically fall back to p2!
        val album = albumDao.getAlbumById("alb1")
        assertEquals("p2", album?.coverFileId)
    }

    @Test
    fun removeLastAssetFromAlbum_setsCoverToNull() = runBlocking {
        insertAsset("single-photo")
        albumDao.insert(AlbumEntity("alb1", "Single Item Album"))
        albumDao.addMediaToAlbum("alb1", listOf("single-photo"))

        assertEquals("single-photo", albumDao.getAlbumById("alb1")?.coverFileId)

        albumDao.removeMediaFromAlbum("alb1", "single-photo")

        val album = albumDao.getAlbumById("alb1")
        assertNull(album?.coverFileId)
    }

    @Test
    fun clearAlbum_setsCoverToNull() = runBlocking {
        insertAsset("p1")
        insertAsset("p2")
        albumDao.insert(AlbumEntity("alb1", "Clear Album Test"))
        albumDao.addMediaToAlbum("alb1", listOf("p1", "p2"))

        assertEquals("p1", albumDao.getAlbumById("alb1")?.coverFileId)

        albumDao.clearAlbum("alb1")

        val album = albumDao.getAlbumById("alb1")
        assertNull(album?.coverFileId)
        assertEquals(0, albumDao.getMediaCountInAlbum("alb1"))
    }

    @Test
    fun addMediaToAlbum_deduplicatesAndPreservesExistingPositions() = runBlocking {
        insertAsset("p1")
        insertAsset("p2")
        insertAsset("p3")

        albumDao.insert(AlbumEntity("alb1", "Dedup Test"))
        albumDao.addMediaToAlbum("alb1", listOf("p1", "p2"))

        var refs = albumDao.getCrossRefsForAlbum("alb1")
        assertEquals(2, refs.size)
        assertEquals(0, refs[0].positionIndex)
        assertEquals(1, refs[1].positionIndex)

        // Now call with duplicate p1, p2 and new p3
        albumDao.addMediaToAlbum("alb1", listOf("p1", "p3", "p3"))

        refs = albumDao.getCrossRefsForAlbum("alb1")
        assertEquals(3, refs.size)
        assertEquals("p1", refs[0].fileId)
        assertEquals(0, refs[0].positionIndex)
        assertEquals("p2", refs[1].fileId)
        assertEquals(1, refs[1].positionIndex)
        assertEquals("p3", refs[2].fileId)
        assertEquals(2, refs[2].positionIndex)
    }

    @Test
    fun deleteAssetReferencedAsCoverByMultipleAlbums_setsAllCoversToNull() = runBlocking {
        insertAsset("shared-cover")
        albumDao.insert(AlbumEntity("alb1", "Album 1", coverFileId = "shared-cover"))
        albumDao.insert(AlbumEntity("alb2", "Album 2", coverFileId = "shared-cover"))

        assertEquals("shared-cover", albumDao.getAlbumById("alb1")?.coverFileId)
        assertEquals("shared-cover", albumDao.getAlbumById("alb2")?.coverFileId)

        // Delete the shared cover asset
        assetDao.deleteById("shared-cover")

        // Both albums must have their cover set to null without SQLite deadlock
        assertNull(albumDao.getAlbumById("alb1")?.coverFileId)
        assertNull(albumDao.getAlbumById("alb2")?.coverFileId)
    }

    @Test
    fun updateAlbumDetails_updatesTitleAndDescription() = runBlocking {
        albumDao.insert(AlbumEntity("alb1", "Old Title", description = "Old Desc"))

        val rows = albumDao.updateAlbumDetails("alb1", "New Title", "New Desc")
        assertEquals(1, rows)

        val updated = albumDao.getAlbumById("alb1")
        assertEquals("New Title", updated?.title)
        assertEquals("New Desc", updated?.description)
    }
}
