package project.kosh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * SQLite many-to-many junction entity associating [AlbumEntity] collections with [MediaAssetEntity] items.
 *
 * Implements Google Photos-style custom album order via [positionIndex], allowing drag-and-drop
 * sequence preservation within individual albums.
 */
@Entity(
    tableName = "album_media_cross_ref",
    primaryKeys = ["album_id", "file_id"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["album_id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["file_id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["album_id"]),
        Index(value = ["file_id"]),
        Index(value = ["album_id", "position_index"])
    ]
)
data class AlbumMediaCrossRef(
    @ColumnInfo(name = "album_id")
    val albumId: String,

    @ColumnInfo(name = "file_id")
    val fileId: String,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "position_index")
    val positionIndex: Int = 0
)
