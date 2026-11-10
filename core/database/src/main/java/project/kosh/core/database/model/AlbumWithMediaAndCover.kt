package project.kosh.core.database.model

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.entity.AlbumMediaCrossRef
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.model.AlbumWithMediaData

/**
 * Full relational composite querying an [AlbumEntity], its cover asset, and all contained [MediaAssetEntity] items.
 */
data class AlbumWithMediaAndCover(
    @Embedded
    val album: AlbumEntity,

    @Relation(
        parentColumn = "cover_file_id",
        entityColumn = "file_id"
    )
    val coverAsset: MediaAssetEntity?,

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
        mediaAssets = mediaAssets.map { it.toDomainModel() },
        coverAsset = coverAsset?.toDomainModel()
    )
}
