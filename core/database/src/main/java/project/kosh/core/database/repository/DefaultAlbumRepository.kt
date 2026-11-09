package project.kosh.core.database.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import project.kosh.core.database.KoshDatabase
import project.kosh.core.database.dao.AlbumDao
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.model.AlbumWithCover
import project.kosh.core.model.Album
import project.kosh.core.model.AlbumWithMediaData
import project.kosh.core.model.MediaAsset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [AlbumRepository] backed by AndroidX Room DAOs.
 */
@Singleton
class DefaultAlbumRepository @Inject constructor(
    private val database: KoshDatabase,
    private val albumDao: AlbumDao
) : AlbumRepository {

    override fun observeAllAlbums(): Flow<List<Album>> =
        albumDao.observeAllAlbums().map { list -> list.map { it.toDomainModel() } }

    override suspend fun getAllAlbums(): List<Album> =
        albumDao.getAllAlbums().map { it.toDomainModel() }

    override fun observeAlbumsWithCover(): Flow<List<AlbumWithCover>> =
        albumDao.observeAlbumsWithCover()

    override fun observeAlbumWithCover(albumId: String): Flow<AlbumWithCover?> =
        albumDao.observeAlbumWithCover(albumId)

    override suspend fun getAlbumWithCover(albumId: String): AlbumWithCover? =
        albumDao.getAlbumWithCover(albumId)

    override fun observeAlbumById(albumId: String): Flow<Album?> =
        albumDao.observeAlbumById(albumId).map { it?.toDomainModel() }

    override suspend fun getAlbumById(albumId: String): Album? =
        albumDao.getAlbumById(albumId)?.toDomainModel()

    override suspend fun createAlbum(
        title: String,
        description: String?,
        coverFileId: String?
    ): Album {
        val now = System.currentTimeMillis()
        val album = Album(
            albumId = UUID.randomUUID().toString(),
            title = title,
            description = description,
            coverFileId = coverFileId,
            createdAt = now,
            updatedAt = now
        )
        albumDao.upsert(AlbumEntity.fromDomainModel(album))
        return album
    }

    override suspend fun updateAlbum(album: Album) {
        albumDao.update(AlbumEntity.fromDomainModel(album.copy(updatedAt = System.currentTimeMillis())))
    }

    override suspend fun updateAlbumDetails(albumId: String, title: String, description: String?) {
        albumDao.updateAlbumDetails(albumId, title, description)
    }

    override suspend fun deleteAlbum(albumId: String): Boolean =
        albumDao.deleteById(albumId) > 0

    override suspend fun clearAllAlbums(): Int =
        database.withTransaction {
            val all = albumDao.getAllAlbums()
            all.forEach { albumDao.delete(it) }
            all.size
        }

    override suspend fun setAlbumCover(albumId: String, coverFileId: String?) {
        albumDao.updateAlbumCover(albumId, coverFileId)
    }

    override suspend fun addMediaToAlbum(albumId: String, fileIds: List<String>) {
        albumDao.addMediaToAlbum(albumId, fileIds)
    }

    override suspend fun removeMediaFromAlbum(albumId: String, fileIds: List<String>) {
        albumDao.removeMediaListFromAlbum(albumId, fileIds)
    }

    override suspend fun clearAlbum(albumId: String) {
        albumDao.clearAlbum(albumId)
    }

    override fun observeMediaForAlbum(albumId: String): Flow<List<MediaAsset>> =
        albumDao.observeMediaForAlbumOrdered(albumId).map { list -> list.map { it.toDomainModel() } }

    override suspend fun getMediaForAlbum(albumId: String): List<MediaAsset> =
        albumDao.getMediaForAlbumOrdered(albumId).map { it.toDomainModel() }

    override suspend fun getAlbumWithMediaData(albumId: String): AlbumWithMediaData? {
        val albumWithCover = albumDao.getAlbumWithCover(albumId) ?: return null
        val mediaAssets = albumDao.getMediaForAlbumOrdered(albumId).map { it.toDomainModel() }
        return AlbumWithMediaData(
            album = albumWithCover.album.toDomainModel(),
            mediaAssets = mediaAssets,
            coverAsset = albumWithCover.coverAsset?.toDomainModel()
        )
    }

    override fun observeAlbumWithMediaData(albumId: String): Flow<AlbumWithMediaData?> =
        combine(
            albumDao.observeAlbumWithCover(albumId),
            albumDao.observeMediaForAlbumOrdered(albumId)
        ) { albumWithCover, mediaEntities ->
            if (albumWithCover == null) {
                null
            } else {
                AlbumWithMediaData(
                    album = albumWithCover.album.toDomainModel(),
                    mediaAssets = mediaEntities.map { it.toDomainModel() },
                    coverAsset = albumWithCover.coverAsset?.toDomainModel()
                )
            }
        }

    override suspend fun reorderAlbum(albumId: String, fileIdsInOrder: List<String>) {
        albumDao.reorderAlbumMedia(albumId, fileIdsInOrder)
    }

    override fun observeMediaCountInAlbum(albumId: String): Flow<Int> =
        albumDao.observeMediaCountInAlbum(albumId)
}
