package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Physical domain namespace isolating sync ledgers.
 *
 * Ledgers are partitioned into distinct namespaces on the server filesystem
 * to eliminate cross-domain lock contention and enable independent sync lifecycles.
 *
 * @property value The wire string representation of the namespace.
 */
@Serializable
enum class SyncNamespace(val value: String) {
    /**
     * Document drive hierarchy, folders, file moves, renames, and deletions.
     */
    @SerialName("files")
    FILES("files"),

    /**
     * Photo and video library timeline, EXIF metadata, albums, and favorites.
     */
    @SerialName("gallery")
    GALLERY("gallery");

    override fun toString(): String = value

    companion object {
        /**
         * Resolves a case-insensitive string value to a [SyncNamespace].
         *
         * @param value The string name ("files" or "gallery").
         * @return Matching [SyncNamespace].
         * @throws IllegalArgumentException If no matching namespace is found.
         */
        fun fromValue(value: String): SyncNamespace =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown sync namespace: $value")
    }
}

/**
 * A single length-prefixed binary delta record parsed from the sync wire stream.
 *
 * Wire framing layout (Big-Endian):
 * - Offset 0..3: 4-byte BE Int payload length ([length])
 * - Offset 4..11: 8-byte BE Long sequence identifier ([sequenceId])
 * - Offset 12..(12+length-1): [payload] containing encrypted CRDT delta bytes
 *
 * @property length Size of the encrypted delta payload in bytes.
 * @property sequenceId Monotonically increasing sequence assigned by server committer.
 * @property payload Encrypted CRDT mutation payload bytes.
 */
data class SyncFrame(
    val length: Int,
    val sequenceId: Long,
    val payload: ByteArray
) {
    init {
        require(length >= 0) { "Frame length cannot be negative: $length" }
        require(length == payload.size) { "Frame length ($length) does not match payload size (${payload.size})" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SyncFrame

        if (length != other.length) return false
        if (sequenceId != other.sequenceId) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = length
        result = 31 * result + sequenceId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "SyncFrame(length=$length, sequenceId=$sequenceId, payloadSize=${payload.size})"
    }
}

/**
 * Wire-level JSON response returned by `POST /api/v1/sync/delta`.
 *
 * @property sequenceId Server-assigned monotonically increasing sequence number.
 * @property fileName Active segment file name where delta was persisted (e.g. `current.log`).
 * @property offset Byte offset in the segment file after appending this record.
 */
@Serializable
data class DeltaAppendResponse(
    @SerialName("sequence_id")
    val sequenceId: Long,

    @SerialName("file_name")
    val fileName: String,

    @SerialName("offset")
    val offset: Long
)

/**
 * Wire-level JSON response returned by `GET /api/v1/sync/snapshot`.
 *
 * Returned when querying the latest state snapshot registered for a namespace.
 *
 * @property id Unique database row identifier of the snapshot record.
 * @property snapshotSeq Sequence number up to which this snapshot materializes state.
 * @property blobHash 64-character hex BLAKE3 hash of the encrypted state snapshot CAS blob.
 * @property createdAt Unix epoch timestamp (in seconds) when the snapshot was registered.
 */
@Serializable
data class SnapshotResponse(
    val id: Long,

    @SerialName("snapshot_seq")
    val snapshotSeq: Long,

    @SerialName("blob_hash")
    val blobHash: String,

    @SerialName("created_at")
    val createdAt: Long
)

/**
 * Wire-level JSON request body for `POST /api/v1/sync/snapshot`.
 *
 * Registers a newly created state snapshot metadata record with the server committer.
 *
 * @property namespace Namespace to which this snapshot belongs.
 * @property snapshotSeq Monotonic sequence ID up to which the snapshot state is materialized.
 * @property blobHash 64-character hex BLAKE3 hash of the uploaded CAS blob containing the snapshot.
 */
@Serializable
data class SnapshotRegisterRequest(
    val namespace: SyncNamespace,

    @SerialName("snapshot_seq")
    val snapshotSeq: Long,

    @SerialName("blob_hash")
    val blobHash: String
)

/**
 * Wire-level JSON response returned by `DELETE /api/v1/sync/prune`.
 *
 * Reports the safe sequence threshold and number of sealed segment files pruned from disk.
 *
 * @property safeSeq Lowest active watermark sequence among all registered devices.
 * @property prunedSegments Number of sealed segment log files unlinked from disk.
 */
@Serializable
data class PruneResponse(
    @SerialName("safe_seq")
    val safeSeq: Long,

    @SerialName("pruned_segments")
    val prunedSegments: Long
)
