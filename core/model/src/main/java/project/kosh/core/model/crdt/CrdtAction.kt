package project.kosh.core.model.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Sealed hierarchy of Delta-CRDT mutations replicated across Kosh client devices.
 *
 * Each action represents an idempotent, commutative transition in the gallery state space.
 * Wire serialization is encoded in Protocol Buffers format with explicit tag numbers.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
sealed interface CrdtAction {
    val actionId: String
    val lamportClock: Long
    val deviceId: String
    val timestampEpochMs: Long

    @Serializable
    @SerialName("AssetCreate")
    data class AssetCreate(
        @ProtoNumber(1) override val actionId: String,
        @ProtoNumber(2) override val lamportClock: Long,
        @ProtoNumber(3) override val deviceId: String,
        @ProtoNumber(4) override val timestampEpochMs: Long,
        @ProtoNumber(5) val fileId: String,
        @ProtoNumber(6) val mediaType: String,
        @ProtoNumber(7) val width: Int? = null,
        @ProtoNumber(8) val height: Int? = null,
        @ProtoNumber(9) val durationMs: Long? = null,
        @ProtoNumber(10) val fileSize: Long,
        @ProtoNumber(11) val capturedAt: Long,
        @ProtoNumber(12) val plaintextSha256: String,
        @ProtoNumber(13) val ciphertextHash: String,
        @ProtoNumber(14) val thumbnailCiphertextHash: String? = null,
        @ProtoNumber(15) val wrappedDek: String,
        @ProtoNumber(16) val deviceMake: String? = null,
        @ProtoNumber(17) val deviceModel: String? = null,
        @ProtoNumber(18) val lensModel: String? = null,
        @ProtoNumber(19) val focalLength: Double? = null,
        @ProtoNumber(20) val aperture: Double? = null,
        @ProtoNumber(21) val iso: Int? = null,
        @ProtoNumber(22) val exposureTime: Double? = null,
        @ProtoNumber(23) val latitude: Double? = null,
        @ProtoNumber(24) val longitude: Double? = null,
        @ProtoNumber(25) val altitude: Double? = null
    ) : CrdtAction

    @Serializable
    @SerialName("AssetDelete")
    data class AssetDelete(
        @ProtoNumber(1) override val actionId: String,
        @ProtoNumber(2) override val lamportClock: Long,
        @ProtoNumber(3) override val deviceId: String,
        @ProtoNumber(4) override val timestampEpochMs: Long,
        @ProtoNumber(5) val fileId: String
    ) : CrdtAction

    @Serializable
    @SerialName("AlbumCreate")
    data class AlbumCreate(
        @ProtoNumber(1) override val actionId: String,
        @ProtoNumber(2) override val lamportClock: Long,
        @ProtoNumber(3) override val deviceId: String,
        @ProtoNumber(4) override val timestampEpochMs: Long,
        @ProtoNumber(5) val albumId: String,
        @ProtoNumber(6) val title: String,
        @ProtoNumber(7) val description: String? = null,
        @ProtoNumber(8) val coverFileId: String? = null
    ) : CrdtAction

    @Serializable
    @SerialName("AlbumDelete")
    data class AlbumDelete(
        @ProtoNumber(1) override val actionId: String,
        @ProtoNumber(2) override val lamportClock: Long,
        @ProtoNumber(3) override val deviceId: String,
        @ProtoNumber(4) override val timestampEpochMs: Long,
        @ProtoNumber(5) val albumId: String
    ) : CrdtAction

    @Serializable
    @SerialName("AlbumUpdateTitle")
    data class AlbumUpdateTitle(
        @ProtoNumber(1) override val actionId: String,
        @ProtoNumber(2) override val lamportClock: Long,
        @ProtoNumber(3) override val deviceId: String,
        @ProtoNumber(4) override val timestampEpochMs: Long,
        @ProtoNumber(5) val albumId: String,
        @ProtoNumber(6) val newTitle: String,
        @ProtoNumber(7) val newDescription: String? = null
    ) : CrdtAction

    @Serializable
    @SerialName("AlbumAddMedia")
    data class AlbumAddMedia(
        @ProtoNumber(1) override val actionId: String,
        @ProtoNumber(2) override val lamportClock: Long,
        @ProtoNumber(3) override val deviceId: String,
        @ProtoNumber(4) override val timestampEpochMs: Long,
        @ProtoNumber(5) val albumId: String,
        @ProtoNumber(6) val fileId: String
    ) : CrdtAction

    @Serializable
    @SerialName("AlbumRemoveMedia")
    data class AlbumRemoveMedia(
        @ProtoNumber(1) override val actionId: String,
        @ProtoNumber(2) override val lamportClock: Long,
        @ProtoNumber(3) override val deviceId: String,
        @ProtoNumber(4) override val timestampEpochMs: Long,
        @ProtoNumber(5) val albumId: String,
        @ProtoNumber(6) val fileId: String
    ) : CrdtAction
}
