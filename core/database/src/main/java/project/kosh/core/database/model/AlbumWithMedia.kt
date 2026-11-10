package project.kosh.core.database.model

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.entity.AlbumMediaCrossRef
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.model.AlbumWithMediaData

/**
 * Many-to-many relational composite querying an [AlbumEntity] along with its assigned [MediaAssetEntity] items.
 */
data class AlbumWithMedia(
    @Embedded
    val album: AlbumEntity,

    @Relation(
        parentColumn = "album_id",
        entityColumn = "file_id",
        associateBy = Junction(
            value = AlbumMediaCrossRef::class,
            parentColumn = "album_id",
            entityColumn = "file_id"
        )
    )
    val mediaAssets: List<MediaAssetEntity>
) {
    fun toDomainModel(): AlbumWithMediaData = AlbumWithMediaData(
        album = album.toDomainModel(),
        mediaAssets = mediaAssets.map { it.toDomainModel() }
    )
}
