package project.kosh.core.database.repository

import kotlinx.coroutines.flow.Flow
import project.kosh.core.database.model.AlbumWithCover
import project.kosh.core.model.Album
import project.kosh.core.model.AlbumWithMediaData
import project.kosh.core.model.MediaAsset

/**
 * Domain repository managing Google Photos-style collections, cover imagery, and item sequencing.
 */
interface AlbumRepository {

    fun observeAllAlbums(): Flow<List<Album>>

    suspend fun getAllAlbums(): List<Album>

    fun observeAlbumsWithCover(): Flow<List<AlbumWithCover>>

    fun observeAlbumWithCover(albumId: String): Flow<AlbumWithCover?>

    suspend fun getAlbumWithCover(albumId: String): AlbumWithCover?

    fun observeAlbumById(albumId: String): Flow<Album?>

    suspend fun getAlbumById(albumId: String): Album?

    suspend fun createAlbum(
        title: String,
        description: String? = null,
        coverFileId: String? = null
    ): Album

    suspend fun updateAlbum(album: Album)

    suspend fun updateAlbumDetails(albumId: String, title: String, description: String?)

    suspend fun deleteAlbum(albumId: String): Boolean

    suspend fun clearAllAlbums(): Int

    suspend fun setAlbumCover(albumId: String, coverFileId: String?)

    suspend fun addMediaToAlbum(albumId: String, fileIds: List<String>)

    suspend fun removeMediaFromAlbum(albumId: String, fileIds: List<String>)

    suspend fun clearAlbum(albumId: String)

    fun observeMediaForAlbum(albumId: String): Flow<List<MediaAsset>>

    suspend fun getMediaForAlbum(albumId: String): List<MediaAsset>

    suspend fun getAlbumWithMediaData(albumId: String): AlbumWithMediaData?

    fun observeAlbumWithMediaData(albumId: String): Flow<AlbumWithMediaData?>

    suspend fun reorderAlbum(albumId: String, fileIdsInOrder: List<String>)

    fun observeMediaCountInAlbum(albumId: String): Flow<Int>
}
