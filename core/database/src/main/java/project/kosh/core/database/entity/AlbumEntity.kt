package project.kosh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import project.kosh.core.model.Album

/**
 * SQLite Room entity representing a Google Photos-style user album collection.
 *
 * Maintains an optional reference to a cover image ([coverFileId]) with `SET_NULL` cascade
 * behaviour: if the cover photo is deleted from the vault, the album persists with null cover.
 * Replicated via Delta-CRDT with [lamportClock], [deviceId], and [isDeleted] tombstones.
 */
@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["file_id"],
            childColumns = ["cover_file_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["cover_file_id"]),
        Index(value = ["created_at"]),
        Index(value = ["updated_at"]),
        Index(value = ["is_deleted"])
    ]
)
data class AlbumEntity(
    @PrimaryKey
    @ColumnInfo(name = "album_id")
    val albumId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "cover_file_id")
    val coverFileId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "lamport_clock", defaultValue = "0")
    val lamportClock: Long = 0L,

    @ColumnInfo(name = "device_id", defaultValue = "''")
    val deviceId: String = "",

    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false
) {
    /**
     * Converts this database entity into the clean domain [Album] model.
     */
    fun toDomainModel(): Album = Album(
        albumId = albumId,
        title = title,
        description = description,
        coverFileId = coverFileId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lamportClock = lamportClock,
        deviceId = deviceId,
        isDeleted = isDeleted
    )

    companion object {
        /**
         * Maps a domain [Album] to an insertable [AlbumEntity].
         */
        fun fromDomainModel(domain: Album): AlbumEntity = AlbumEntity(
            albumId = domain.albumId,
            title = domain.title,
            description = domain.description,
            coverFileId = domain.coverFileId,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt,
            lamportClock = domain.lamportClock,
            deviceId = domain.deviceId,
            isDeleted = domain.isDeleted
        )
    }
}
