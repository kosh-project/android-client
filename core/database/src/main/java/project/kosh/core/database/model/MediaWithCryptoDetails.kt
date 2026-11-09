package project.kosh.core.database.model

import androidx.room.Embedded
import androidx.room.Relation
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity

/**
 * 1-to-1 relational composite combining a [MediaAssetEntity] with its [MediaCryptoMappingEntity].
 */
data class MediaWithCryptoDetails(
    @Embedded
    val asset: MediaAssetEntity,

    @Relation(
        parentColumn = "file_id",
        entityColumn = "file_id"
    )
    val cryptoMapping: MediaCryptoMappingEntity?
)
