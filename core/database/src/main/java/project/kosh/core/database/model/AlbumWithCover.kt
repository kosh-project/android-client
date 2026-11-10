package project.kosh.core.database.model

import androidx.room.Embedded
import androidx.room.Relation
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.entity.MediaAssetEntity

/**
 * Relational composite associating an [AlbumEntity] with its resolved cover [MediaAssetEntity].
 */
data class AlbumWithCover(
    @Embedded
    val album: AlbumEntity,

    @Relation(
        parentColumn = "cover_file_id",
        entityColumn = "file_id"
    )
    val coverAsset: MediaAssetEntity?
)
