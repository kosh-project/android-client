package project.kosh.core.model.snapshot

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import project.kosh.core.model.AssetTag
import project.kosh.core.model.UploadStatus

/**
 * Protocol Buffers DTO representing a MediaAsset row within a gallery snapshot.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SnapshotMediaAsset(
    @ProtoNumber(1) val fileId: String,
    @ProtoNumber(2) val thumbnailHash: String? = null,
    @ProtoNumber(3) val mediaType: String,
    @ProtoNumber(4) val width: Int? = null,
    @ProtoNumber(5) val height: Int? = null,
    @ProtoNumber(6) val durationMs: Long? = null,
    @ProtoNumber(7) val fileSize: Long,
    @ProtoNumber(8) val deviceMake: String? = null,
    @ProtoNumber(9) val deviceModel: String? = null,
    @ProtoNumber(10) val lensModel: String? = null,
    @ProtoNumber(11) val focalLength: Double? = null,
    @ProtoNumber(12) val aperture: Double? = null,
    @ProtoNumber(13) val iso: Int? = null,
    @ProtoNumber(14) val exposureTime: Double? = null,
    @ProtoNumber(15) val latitude: Double? = null,
    @ProtoNumber(16) val longitude: Double? = null,
    @ProtoNumber(17) val altitude: Double? = null,
    @ProtoNumber(18) val capturedAt: Long,
    @ProtoNumber(19) val createdAt: Long = 0L,
    @ProtoNumber(20) val updatedAt: Long = 0L,
    @ProtoNumber(21) val assetTag: AssetTag = AssetTag.GALLERY_ITEM,
    @ProtoNumber(22) val lamportClock: Long = 0L,
    @ProtoNumber(23) val deviceId: String = "",
    @ProtoNumber(24) val isDeleted: Boolean = false
)

/**
 * Protocol Buffers DTO representing a MediaCryptoMapping row within a gallery snapshot.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SnapshotMediaCrypto(
    @ProtoNumber(1) val fileId: String,
    @ProtoNumber(2) val plaintextSha256: String,
    @ProtoNumber(3) val ciphertextHash: String? = null,
    @ProtoNumber(4) val thumbnailCiphertextHash: String? = null,
    @ProtoNumber(5) val wrappedDek: String,
    @ProtoNumber(6) val wrappedThumbnailDek: String? = null,
    @ProtoNumber(7) val uploadStatus: UploadStatus = UploadStatus.PENDING,
    @ProtoNumber(8) val lastSyncedAt: Long? = null,
    @ProtoNumber(9) val errorMessage: String? = null,
    @ProtoNumber(10) val lamportClock: Long = 0L,
    @ProtoNumber(11) val deviceId: String = "",
    @ProtoNumber(12) val isDeleted: Boolean = false,
    @ProtoNumber(13) val thumbnailFileId: String? = null
)

/**
 * Protocol Buffers DTO representing an Album row within a gallery snapshot.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SnapshotAlbum(
    @ProtoNumber(1) val albumId: String,
    @ProtoNumber(2) val title: String,
    @ProtoNumber(3) val description: String? = null,
    @ProtoNumber(4) val coverFileId: String? = null,
    @ProtoNumber(5) val createdAt: Long = 0L,
    @ProtoNumber(6) val updatedAt: Long = 0L,
    @ProtoNumber(7) val lamportClock: Long = 0L,
    @ProtoNumber(8) val deviceId: String = "",
    @ProtoNumber(9) val isDeleted: Boolean = false
)

/**
 * Protocol Buffers DTO representing an AlbumMediaCrossRef junction row within a gallery snapshot.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SnapshotAlbumCrossRef(
    @ProtoNumber(1) val albumId: String,
    @ProtoNumber(2) val fileId: String,
    @ProtoNumber(3) val addedAt: Long = 0L,
    @ProtoNumber(4) val positionIndex: Int = 0,
    @ProtoNumber(5) val lamportClock: Long = 0L,
    @ProtoNumber(6) val deviceId: String = "",
    @ProtoNumber(7) val isDeleted: Boolean = false
)

/**
 * Encrypted point-in-time state snapshot bundle for fast-bootstrap client synchronization.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GallerySnapshotBundle(
    @ProtoNumber(1) val version: Int = 1,
    @ProtoNumber(2) val generatedAtEpochMs: Long,
    @ProtoNumber(3) val watermarkSeq: Long,
    @ProtoNumber(4) val assets: List<SnapshotMediaAsset> = emptyList(),
    @ProtoNumber(5) val cryptoMappings: List<SnapshotMediaCrypto> = emptyList(),
    @ProtoNumber(6) val albums: List<SnapshotAlbum> = emptyList(),
    @ProtoNumber(7) val albumCrossRefs: List<SnapshotAlbumCrossRef> = emptyList()
)
