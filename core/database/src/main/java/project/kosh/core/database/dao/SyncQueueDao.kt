package project.kosh.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import project.kosh.core.database.entity.SyncQueueEntity

/**
 * Data Access Object for [SyncQueueEntity].
 *
 * Manages staging of local Delta-CRDT operations while offline and handles batching/pruning.
 */
@Dao
interface SyncQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: SyncQueueEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueAll(entries: List<SyncQueueEntity>)

    @Query("SELECT * FROM sync_action_queue WHERE namespace = :namespace AND status = 'PENDING' ORDER BY lamport_clock ASC, created_at ASC LIMIT :limit")
    suspend fun getPendingActions(namespace: String = "gallery", limit: Int = 100): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_action_queue WHERE namespace = :namespace AND status = 'PENDING' ORDER BY lamport_clock ASC, created_at ASC")
    suspend fun getAllPending(namespace: String = "gallery"): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_action_queue WHERE namespace = :namespace AND status = 'PENDING'")
    fun observePendingCount(namespace: String = "gallery"): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_action_queue WHERE namespace = :namespace AND status = 'PENDING'")
    suspend fun getPendingCount(namespace: String = "gallery"): Int

    @Query("UPDATE sync_action_queue SET status = :status WHERE queue_id IN (:queueIds)")
    suspend fun updateStatus(queueIds: List<Long>, status: String): Int

    @Query("DELETE FROM sync_action_queue WHERE namespace = :namespace AND status = 'COMMITTED'")
    suspend fun deleteCommitted(namespace: String = "gallery"): Int

    @Query("DELETE FROM sync_action_queue WHERE queue_id = :queueId")
    suspend fun deleteById(queueId: Long): Int

    @Query("DELETE FROM sync_action_queue WHERE queue_id IN (:queueIds)")
    suspend fun deleteByIds(queueIds: List<Long>): Int

    @Query("DELETE FROM sync_action_queue WHERE entity_id = :entityId")
    suspend fun deleteByEntityId(entityId: String): Int

    @Query("DELETE FROM sync_action_queue WHERE action_id = :actionId")
    suspend fun deleteByActionId(actionId: String): Int

    @Query("DELETE FROM sync_action_queue WHERE namespace = :namespace")
    suspend fun clearQueue(namespace: String = "gallery"): Int

    @Query("UPDATE sync_action_queue SET lamport_clock = :newClock, payload_protobuf = :payloadProtobuf, byte_size = :byteSize WHERE queue_id = :queueId")
    suspend fun updateClockAndPayload(queueId: Long, newClock: Long, payloadProtobuf: ByteArray, byteSize: Int): Int
}
