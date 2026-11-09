package project.kosh.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.entity.AlbumMediaCrossRef
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.model.AlbumWithCover
import project.kosh.core.database.model.AlbumWithMedia
import project.kosh.core.database.model.AlbumWithMediaAndCover

/**
 * Data Access Object for Google Photos-style collections ([AlbumEntity] and [AlbumMediaCrossRef]).
 *
 * Supports album creation, cover image resolution, cross-reference associations,
 * and custom album sequence reordering.
 */
@Dao
interface AlbumDao {

    @Upsert
    suspend fun upsert(album: AlbumEntity)

    @Upsert
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(album: AlbumEntity)

    @Update
    suspend fun update(album: AlbumEntity)

    @Delete
    suspend fun delete(album: AlbumEntity)

    @Query("DELETE FROM albums WHERE album_id = :albumId")
    suspend fun deleteById(albumId: String): Int

    @Query("SELECT * FROM albums WHERE album_id = :albumId")
    suspend fun getAlbumById(albumId: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE album_id = :albumId")
    fun observeAlbumById(albumId: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums ORDER BY updated_at DESC")
    suspend fun getAllAlbums(): List<AlbumEntity>

    @Query("SELECT * FROM albums ORDER BY updated_at DESC")
    fun observeAllAlbums(): Flow<List<AlbumEntity>>

    @Transaction
    @Query("SELECT * FROM albums ORDER BY updated_at DESC")
    fun observeAlbumsWithCover(): Flow<List<AlbumWithCover>>

    @Transaction
    @Query("SELECT * FROM albums WHERE album_id = :albumId")
    suspend fun getAlbumWithMedia(albumId: String): AlbumWithMedia?

    @Transaction
    @Query("SELECT * FROM albums WHERE album_id = :albumId")
    fun observeAlbumWithMedia(albumId: String): Flow<AlbumWithMedia?>

    @Transaction
    @Query("SELECT * FROM albums WHERE album_id = :albumId")
    suspend fun getAlbumWithMediaAndCover(albumId: String): AlbumWithMediaAndCover?

    @Transaction
    @Query("SELECT * FROM albums WHERE album_id = :albumId")
    fun observeAlbumWithMediaAndCover(albumId: String): Flow<AlbumWithMediaAndCover?>

    @Transaction
    @Query("SELECT * FROM albums WHERE album_id = :albumId")
    suspend fun getAlbumWithCover(albumId: String): AlbumWithCover?

    @Transaction
    @Query("SELECT * FROM albums WHERE album_id = :albumId")
    fun observeAlbumWithCover(albumId: String): Flow<AlbumWithCover?>

    @Query("UPDATE albums SET cover_file_id = :coverFileId, updated_at = :updatedAt WHERE album_id = :albumId")
    suspend fun updateAlbumCover(
        albumId: String,
        coverFileId: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE albums SET title = :title, description = :description, updated_at = :updatedAt WHERE album_id = :albumId")
    suspend fun updateAlbumDetails(
        albumId: String,
        title: String,
        description: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    // Junction cross-reference operations

    @Upsert
    suspend fun insertCrossRef(crossRef: AlbumMediaCrossRef)

    @Upsert
    suspend fun insertCrossRefs(crossRefs: List<AlbumMediaCrossRef>)

    @Query("DELETE FROM album_media_cross_ref WHERE album_id = :albumId AND file_id = :fileId")
    suspend fun deleteCrossRefInternal(albumId: String, fileId: String): Int

    @Query("DELETE FROM album_media_cross_ref WHERE album_id = :albumId AND file_id IN (:fileIds)")
    suspend fun deleteCrossRefListInternal(albumId: String, fileIds: List<String>): Int

    @Query("DELETE FROM album_media_cross_ref WHERE album_id = :albumId")
    suspend fun clearCrossRefsInternal(albumId: String): Int

    @Transaction
    suspend fun removeMediaFromAlbum(albumId: String, fileId: String): Int =
        removeMediaListFromAlbum(albumId, listOf(fileId))

    @Transaction
    suspend fun removeMediaListFromAlbum(albumId: String, fileIds: List<String>): Int {
        val removed = deleteCrossRefListInternal(albumId, fileIds)
        if (removed > 0) {
            val album = getAlbumById(albumId)
            if (album != null && album.coverFileId in fileIds) {
                val remaining = getMediaForAlbumOrdered(albumId)
                updateAlbumCover(albumId, remaining.firstOrNull()?.fileId)
            }
        }
        return removed
    }

    @Transaction
    suspend fun clearAlbum(albumId: String): Int {
        val removed = clearCrossRefsInternal(albumId)
        updateAlbumCover(albumId, null)
        return removed
    }

    @Query("SELECT * FROM album_media_cross_ref WHERE album_id = :albumId ORDER BY position_index ASC, added_at ASC")
    suspend fun getCrossRefsForAlbum(albumId: String): List<AlbumMediaCrossRef>

    @Query("SELECT * FROM album_media_cross_ref WHERE album_id = :albumId ORDER BY position_index ASC, added_at ASC")
    fun observeCrossRefsForAlbum(albumId: String): Flow<List<AlbumMediaCrossRef>>

    @Query("""
        SELECT m.* FROM media_assets m
        INNER JOIN album_media_cross_ref r ON m.file_id = r.file_id
        WHERE r.album_id = :albumId
        ORDER BY r.position_index ASC, r.added_at ASC
    """)
    fun observeMediaForAlbumOrdered(albumId: String): Flow<List<MediaAssetEntity>>

    @Query("""
        SELECT m.* FROM media_assets m
        INNER JOIN album_media_cross_ref r ON m.file_id = r.file_id
        WHERE r.album_id = :albumId
        ORDER BY r.position_index ASC, r.added_at ASC
    """)
    suspend fun getMediaForAlbumOrdered(albumId: String): List<MediaAssetEntity>

    @Query("SELECT COUNT(*) FROM album_media_cross_ref WHERE album_id = :albumId")
    suspend fun getMediaCountInAlbum(albumId: String): Int

    @Query("SELECT COUNT(*) FROM album_media_cross_ref WHERE album_id = :albumId")
    fun observeMediaCountInAlbum(albumId: String): Flow<Int>

    @Query("UPDATE album_media_cross_ref SET position_index = :newPosition WHERE album_id = :albumId AND file_id = :fileId")
    suspend fun updatePosition(albumId: String, fileId: String, newPosition: Int): Int

    @Transaction
    suspend fun reorderAlbumMedia(albumId: String, fileIdsInOrder: List<String>) {
        fileIdsInOrder.forEachIndexed { index, fileId ->
            updatePosition(albumId = albumId, fileId = fileId, newPosition = index)
        }
        // Ensure any items in album not specified in fileIdsInOrder are shifted after
        val remaining = getCrossRefsForAlbum(albumId).filterNot { it.fileId in fileIdsInOrder }
        remaining.forEachIndexed { index, ref ->
            updatePosition(albumId = albumId, fileId = ref.fileId, newPosition = fileIdsInOrder.size + index)
        }
    }

    @Transaction
    suspend fun addMediaToAlbum(
        albumId: String,
        fileIds: List<String>,
        startingPosition: Int? = null
    ) {
        if (fileIds.isEmpty()) return
        val existing = getCrossRefsForAlbum(albumId)
        val existingIds = existing.map { it.fileId }.toSet()
        val newIds = fileIds.distinct().filterNot { it in existingIds }
        if (newIds.isEmpty()) return

        val startPos = startingPosition ?: if (existing.isEmpty()) 0 else (existing.maxOf { it.positionIndex } + 1)
        val now = System.currentTimeMillis()
        val refs = newIds.mapIndexed { index, fileId ->
            AlbumMediaCrossRef(
                albumId = albumId,
                fileId = fileId,
                addedAt = now,
                positionIndex = startPos + index
            )
        }
        insertCrossRefs(refs)

        val album = getAlbumById(albumId)
        if (album != null && album.coverFileId == null) {
            updateAlbumCover(albumId, newIds.first())
        }
    }
}
