package project.kosh.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import project.kosh.core.database.entity.SyncWatermarkEntity

/**
 * Data Access Object for [SyncWatermarkEntity].
 *
 * Tracks local read cursor positions for streaming delta recovery.
 */
@Dao
interface SyncWatermarkDao {

    @Query("SELECT * FROM sync_watermarks WHERE namespace = :namespace")
    suspend fun getWatermark(namespace: String): SyncWatermarkEntity?

    @Upsert
    suspend fun upsertWatermark(watermark: SyncWatermarkEntity)

    @Query("""
        UPDATE sync_watermarks 
        SET last_file = :lastFile, 
            last_offset = :lastOffset, 
            last_sequence_id = :lastSequenceId, 
            last_synced_at = :lastSyncedAt 
        WHERE namespace = :namespace
    """)
    suspend fun updatePosition(
        namespace: String,
        lastFile: String,
        lastOffset: Long,
        lastSequenceId: Long,
        lastSyncedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM sync_watermarks WHERE namespace = :namespace")
    suspend fun clearWatermark(namespace: String): Int
}
