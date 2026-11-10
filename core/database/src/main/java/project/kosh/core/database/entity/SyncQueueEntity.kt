package project.kosh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SQLite Room entity staging local Delta-CRDT actions while offline.
 *
 * Sliced into bundles of <= 512 KiB before AEAD encryption and transmission.
 */
@Entity(
    tableName = "sync_action_queue",
    indices = [
        Index(value = ["status", "created_at"]),
        Index(value = ["entity_id"])
    ]
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "queue_id") val queueId: Long = 0,
    @ColumnInfo(name = "action_id") val actionId: String,
    @ColumnInfo(name = "namespace") val namespace: String = "gallery",
    @ColumnInfo(name = "action_type") val actionType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "payload_protobuf") val payloadProtobuf: ByteArray,
    @ColumnInfo(name = "byte_size") val byteSize: Int,
    @ColumnInfo(name = "lamport_clock") val lamportClock: Long,
    @ColumnInfo(name = "status") val status: String = "PENDING", // PENDING, IN_FLIGHT, COMMITTED
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SyncQueueEntity

        if (queueId != other.queueId) return false
        if (actionId != other.actionId) return false
        if (namespace != other.namespace) return false
        if (actionType != other.actionType) return false
        if (entityId != other.entityId) return false
        if (!payloadProtobuf.contentEquals(other.payloadProtobuf)) return false
        if (byteSize != other.byteSize) return false
        if (lamportClock != other.lamportClock) return false
        if (status != other.status) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = queueId.hashCode()
        result = 31 * result + actionId.hashCode()
        result = 31 * result + namespace.hashCode()
        result = 31 * result + actionType.hashCode()
        result = 31 * result + entityId.hashCode()
        result = 31 * result + payloadProtobuf.contentHashCode()
        result = 31 * result + byteSize
        result = 31 * result + lamportClock.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
