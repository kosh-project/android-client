package project.kosh.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import project.kosh.core.database.dao.AlbumDao
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.dao.MediaCryptoDao
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.entity.AlbumMediaCrossRef
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity

/**
 * Primary AndroidX Room SQLite database for Kosh local storage.
 *
 * Persists gallery media catalog, EXIF metadata, wrapped DEK envelopes,
 * and user album collections offline.
 */
@Database(
    entities = [
        MediaAssetEntity::class,
        MediaCryptoMappingEntity::class,
        AlbumEntity::class,
        AlbumMediaCrossRef::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(DatabaseConverter::class)
abstract class KoshDatabase : RoomDatabase() {

    abstract fun mediaAssetDao(): MediaAssetDao

    abstract fun mediaCryptoDao(): MediaCryptoDao

    abstract fun albumDao(): AlbumDao

    companion object {
        const val DATABASE_NAME = "kosh_vault.db"
    }
}
