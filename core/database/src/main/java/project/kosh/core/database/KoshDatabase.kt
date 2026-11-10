package project.kosh.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import project.kosh.core.database.dao.AlbumDao
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.dao.MediaCryptoDao
import project.kosh.core.database.dao.SyncQueueDao
import project.kosh.core.database.dao.SyncWatermarkDao
import project.kosh.core.database.entity.AlbumEntity
import project.kosh.core.database.entity.AlbumMediaCrossRef
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.database.entity.SyncQueueEntity
import project.kosh.core.database.entity.SyncWatermarkEntity

/**
 * Primary AndroidX Room SQLite database for Kosh local storage.
 *
 * Persists gallery media catalog, EXIF metadata, wrapped DEK envelopes,
 * user album collections offline, Delta-CRDT action queue, and sync watermarks.
 */
@Database(
    entities = [
        MediaAssetEntity::class,
        MediaCryptoMappingEntity::class,
        AlbumEntity::class,
        AlbumMediaCrossRef::class,
        SyncQueueEntity::class,
        SyncWatermarkEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(DatabaseConverter::class)
abstract class KoshDatabase : RoomDatabase() {

    abstract fun mediaAssetDao(): MediaAssetDao

    abstract fun mediaCryptoDao(): MediaCryptoDao

    abstract fun albumDao(): AlbumDao

    abstract fun syncQueueDao(): SyncQueueDao

    abstract fun syncWatermarkDao(): SyncWatermarkDao

    companion object {
        const val DATABASE_NAME = "kosh_vault.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Evolve media_assets
                db.execSQL("ALTER TABLE media_assets ADD COLUMN lamport_clock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_assets ADD COLUMN device_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_assets ADD COLUMN is_deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_assets_is_deleted ON media_assets(is_deleted)")

                // 2. Evolve media_crypto_mapping: rename plaintext_hash -> plaintext_sha256, add CRDT columns
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `media_crypto_mapping_new` (
                        `file_id` TEXT NOT NULL,
                        `plaintext_sha256` TEXT NOT NULL,
                        `ciphertext_hash` TEXT,
                        `thumbnail_ciphertext_hash` TEXT,
                        `wrapped_dek` TEXT NOT NULL,
                        `wrapped_thumbnail_dek` TEXT,
                        `upload_status` TEXT NOT NULL,
                        `last_synced_at` INTEGER,
                        `error_message` TEXT,
                        `lamport_clock` INTEGER NOT NULL DEFAULT 0,
                        `device_id` TEXT NOT NULL DEFAULT '',
                        `is_deleted` INTEGER NOT NULL DEFAULT 0,
                        `thumbnail_file_id` TEXT,
                        PRIMARY KEY(`file_id`),
                        FOREIGN KEY(`file_id`) REFERENCES `media_assets`(`file_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO `media_crypto_mapping_new` (
                        `file_id`, `plaintext_sha256`, `ciphertext_hash`, `thumbnail_ciphertext_hash`,
                        `wrapped_dek`, `wrapped_thumbnail_dek`, `upload_status`, `last_synced_at`,
                        `error_message`, `lamport_clock`, `device_id`, `is_deleted`, `thumbnail_file_id`
                    )
                    SELECT
                        `file_id`, `plaintext_hash`, `ciphertext_hash`, `thumbnail_ciphertext_hash`,
                        `wrapped_dek`, `wrapped_thumbnail_dek`, `upload_status`, `last_synced_at`,
                        `error_message`, 0, '', 0, NULL
                    FROM `media_crypto_mapping`
                """.trimIndent())

                db.execSQL("DROP TABLE `media_crypto_mapping`")
                db.execSQL("ALTER TABLE `media_crypto_mapping_new` RENAME TO `media_crypto_mapping`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_crypto_mapping_plaintext_sha256` ON `media_crypto_mapping` (`plaintext_sha256`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_crypto_mapping_ciphertext_hash` ON `media_crypto_mapping` (`ciphertext_hash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_crypto_mapping_upload_status` ON `media_crypto_mapping` (`upload_status`)")

                // 3. Evolve albums
                db.execSQL("ALTER TABLE albums ADD COLUMN lamport_clock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE albums ADD COLUMN device_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE albums ADD COLUMN is_deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_albums_is_deleted ON albums(is_deleted)")

                // 4. Evolve album_media_cross_ref
                db.execSQL("ALTER TABLE album_media_cross_ref ADD COLUMN lamport_clock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE album_media_cross_ref ADD COLUMN device_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE album_media_cross_ref ADD COLUMN is_deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_album_media_cross_ref_is_deleted ON album_media_cross_ref(is_deleted)")

                // 5. Create sync_action_queue
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sync_action_queue` (
                        `queue_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `action_id` TEXT NOT NULL,
                        `namespace` TEXT NOT NULL,
                        `action_type` TEXT NOT NULL,
                        `entity_id` TEXT NOT NULL,
                        `payload_protobuf` BLOB NOT NULL,
                        `byte_size` INTEGER NOT NULL,
                        `lamport_clock` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_action_queue_status_created_at` ON `sync_action_queue` (`status`, `created_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_action_queue_entity_id` ON `sync_action_queue` (`entity_id`)")

                // 6. Create sync_watermarks
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sync_watermarks` (
                        `namespace` TEXT NOT NULL,
                        `last_file` TEXT NOT NULL,
                        `last_offset` INTEGER NOT NULL,
                        `last_sequence_id` INTEGER NOT NULL,
                        `last_synced_at` INTEGER NOT NULL,
                        PRIMARY KEY(`namespace`)
                    )
                """.trimIndent())
            }
        }
    }
}
