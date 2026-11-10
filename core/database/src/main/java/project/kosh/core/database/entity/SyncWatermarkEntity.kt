package project.kosh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SQLite Room entity tracking the device's read watermark and segment positions.
 *
 * Persists [lastFile] and [lastOffset] to allow incremental delta replay on reconnection.
 */
@Entity(tableName = "sync_watermarks")
data class SyncWatermarkEntity(
    @PrimaryKey
    @ColumnInfo(name = "namespace")
    val namespace: String,

    @ColumnInfo(name = "last_file")
    val lastFile: String = "current.log",

    @ColumnInfo(name = "last_offset")
    val lastOffset: Long = 0L,

    @ColumnInfo(name = "last_sequence_id")
    val lastSequenceId: Long = 0L,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = 0L
)
