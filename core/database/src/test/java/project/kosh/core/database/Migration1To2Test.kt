package project.kosh.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration1To2Test {

    @Test
    fun testMigration1To2_preservesDataAndAddsColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("test_migration.db")
        dbFile.delete()

        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration.db")
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Create Schema Version 1
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `media_assets` (
                            `file_id` TEXT NOT NULL,
                            `thumbnail_hash` TEXT,
                            `media_type` TEXT NOT NULL,
                            `width` INTEGER,
                            `height` INTEGER,
                            `duration_ms` INTEGER,
                            `file_size` INTEGER NOT NULL,
                            `device_make` TEXT,
                            `device_model` TEXT,
                            `lens_model` TEXT,
                            `focal_length` REAL,
                            `aperture` REAL,
                            `iso` INTEGER,
                            `exposure_time` REAL,
                            `latitude` REAL,
                            `longitude` REAL,
                            `altitude` REAL,
                            `captured_at` INTEGER NOT NULL,
                            `created_at` INTEGER NOT NULL,
                            `updated_at` INTEGER NOT NULL,
                            `asset_tag` TEXT NOT NULL,
                            PRIMARY KEY(`file_id`)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `media_crypto_mapping` (
                            `file_id` TEXT NOT NULL,
                            `plaintext_hash` TEXT NOT NULL,
                            `ciphertext_hash` TEXT,
                            `thumbnail_ciphertext_hash` TEXT,
                            `wrapped_dek` TEXT NOT NULL,
                            `wrapped_thumbnail_dek` TEXT,
                            `upload_status` TEXT NOT NULL,
                            `last_synced_at` INTEGER,
                            `error_message` TEXT,
                            PRIMARY KEY(`file_id`),
                            FOREIGN KEY(`file_id`) REFERENCES `media_assets`(`file_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `albums` (
                            `album_id` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `description` TEXT,
                            `cover_file_id` TEXT,
                            `created_at` INTEGER NOT NULL,
                            `updated_at` INTEGER NOT NULL,
                            PRIMARY KEY(`album_id`)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `album_media_cross_ref` (
                            `album_id` TEXT NOT NULL,
                            `file_id` TEXT NOT NULL,
                            `added_at` INTEGER NOT NULL,
                            `position_index` INTEGER NOT NULL,
                            PRIMARY KEY(`album_id`, `file_id`)
                        )
                    """.trimIndent())

                    // Insert sample v1 data
                    db.execSQL("INSERT INTO media_assets (file_id, media_type, file_size, captured_at, created_at, updated_at, asset_tag) VALUES ('f1', 'image/jpeg', 1024, 1000, 1000, 1000, 'GALLERY_ITEM')")
                    db.execSQL("INSERT INTO media_crypto_mapping (file_id, plaintext_hash, wrapped_dek, upload_status) VALUES ('f1', 'hash123', 'dek60bytes', 'SYNCED')")
                    db.execSQL("INSERT INTO albums (album_id, title, created_at, updated_at) VALUES ('a1', 'My Album', 2000, 2000)")
                    db.execSQL("INSERT INTO album_media_cross_ref (album_id, file_id, added_at, position_index) VALUES ('a1', 'f1', 2000, 0)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = helperFactory.create(config)
        val db = helper.writableDatabase

        // Execute MIGRATION_1_2
        KoshDatabase.MIGRATION_1_2.migrate(db)

        // Verify media_crypto_mapping has plaintext_sha256 and copied data
        val cryptoCursor = db.query("SELECT file_id, plaintext_sha256, wrapped_dek, lamport_clock, device_id, is_deleted, thumbnail_file_id FROM media_crypto_mapping WHERE file_id = 'f1'")
        assertTrue(cryptoCursor.moveToFirst())
        assertEquals("f1", cryptoCursor.getString(0))
        assertEquals("hash123", cryptoCursor.getString(1))
        assertEquals("dek60bytes", cryptoCursor.getString(2))
        assertEquals(0L, cryptoCursor.getLong(3))
        assertEquals("", cryptoCursor.getString(4))
        assertEquals(0, cryptoCursor.getInt(5))
        assertTrue(cryptoCursor.isNull(6))
        cryptoCursor.close()

        // Verify media_assets has lamport_clock, device_id, is_deleted
        val assetCursor = db.query("SELECT file_id, lamport_clock, device_id, is_deleted FROM media_assets WHERE file_id = 'f1'")
        assertTrue(assetCursor.moveToFirst())
        assertEquals("f1", assetCursor.getString(0))
        assertEquals(0L, assetCursor.getLong(1))
        assertEquals("", assetCursor.getString(2))
        assertEquals(0, assetCursor.getInt(3))
        assetCursor.close()

        // Verify sync_action_queue table exists
        val queueCursor = db.query("SELECT count(*) FROM sync_action_queue")
        assertTrue(queueCursor.moveToFirst())
        assertEquals(0, queueCursor.getInt(0))
        queueCursor.close()

        // Verify sync_watermarks table exists
        val watermarkCursor = db.query("SELECT count(*) FROM sync_watermarks")
        assertTrue(watermarkCursor.moveToFirst())
        assertEquals(0, watermarkCursor.getInt(0))
        watermarkCursor.close()

        db.close()
        dbFile.delete()
    }
}
