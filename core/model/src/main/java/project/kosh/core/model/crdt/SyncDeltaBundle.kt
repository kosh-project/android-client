package project.kosh.core.model.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Protocol Buffers wire transmission bundle aggregating an ordered list of Delta-CRDT actions.
 *
 * Sliced to a maximum size of 512 KiB before AEAD encryption and transmission to the Kosh committer.
 *
 * @property bundleId Unique identifier for this batch/slice.
 * @property deviceId Originating device ID that generated these mutations.
 * @property minClock Minimum Lamport clock value across the bundled actions.
 * @property maxClock Maximum Lamport clock value across the bundled actions.
 * @property actions Ordered list of [CrdtAction] transitions.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SyncDeltaBundle(
    @ProtoNumber(1) val bundleId: String,
    @ProtoNumber(2) val deviceId: String,
    @ProtoNumber(3) val minClock: Long,
    @ProtoNumber(4) val maxClock: Long,
    @ProtoNumber(5) val actions: List<CrdtAction>
)
