package project.kosh.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import project.kosh.core.model.AssetTag
import project.kosh.core.model.ExifMetadata
import project.kosh.core.model.MediaAsset

/**
 * SQLite Room entity for physical media assets and their associated EXIF metadata.
 *
 * Indexed along temporal dimensions ([capturedAt], [createdAt]) to allow zero-lag
 * reverse-chronological timeline rendering (Google Photos-style grid) and MIME type filtering.
 */
@Entity(
    tableName = "media_assets",
    indices = [
        Index(value = ["captured_at"]),
        Index(value = ["captured_at", "file_id"]),
        Index(value = ["created_at"]),
        Index(value = ["media_type"])
    ]
)
data class MediaAssetEntity(
    @PrimaryKey
    @ColumnInfo(name = "file_id")
    val fileId: String,

    @ColumnInfo(name = "thumbnail_hash")
    val thumbnailHash: String? = null,

    @ColumnInfo(name = "media_type")
    val mediaType: String,

    @ColumnInfo(name = "width")
    val width: Int? = null,

    @ColumnInfo(name = "height")
    val height: Int? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,

    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    @ColumnInfo(name = "device_make")
    val deviceMake: String? = null,

    @ColumnInfo(name = "device_model")
    val deviceModel: String? = null,

    @ColumnInfo(name = "lens_model")
    val lensModel: String? = null,

    @ColumnInfo(name = "focal_length")
    val focalLength: Double? = null,

    @ColumnInfo(name = "aperture")
    val aperture: Double? = null,

    @ColumnInfo(name = "iso")
    val iso: Int? = null,

    @ColumnInfo(name = "exposure_time")
    val exposureTime: Double? = null,

    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    @ColumnInfo(name = "altitude")
    val altitude: Double? = null,

    @ColumnInfo(name = "captured_at")
    val capturedAt: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "asset_tag")
    val assetTag: AssetTag = AssetTag.GALLERY_ITEM
) {
    /**
     * Converts this database entity into the clean domain [MediaAsset] model.
     */
    fun toDomainModel(): MediaAsset = MediaAsset(
        fileId = fileId,
        thumbnailHash = thumbnailHash,
        mediaType = mediaType,
        width = width,
        height = height,
        durationMs = durationMs,
        fileSize = fileSize,
        exif = ExifMetadata(
            deviceMake = deviceMake,
            deviceModel = deviceModel,
            lensModel = lensModel,
            focalLength = focalLength,
            aperture = aperture,
            iso = iso,
            exposureTime = exposureTime,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude
        ),
        capturedAt = capturedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        assetTag = assetTag
    )

    companion object {
        /**
         * Maps a domain [MediaAsset] to an insertable [MediaAssetEntity].
         */
        fun fromDomainModel(domain: MediaAsset): MediaAssetEntity = MediaAssetEntity(
            fileId = domain.fileId,
            thumbnailHash = domain.thumbnailHash,
            mediaType = domain.mediaType,
            width = domain.width,
            height = domain.height,
            durationMs = domain.durationMs,
            fileSize = domain.fileSize,
            deviceMake = domain.exif.deviceMake,
            deviceModel = domain.exif.deviceModel,
            lensModel = domain.exif.lensModel,
            focalLength = domain.exif.focalLength,
            aperture = domain.exif.aperture,
            iso = domain.exif.iso,
            exposureTime = domain.exif.exposureTime,
            latitude = domain.exif.latitude,
            longitude = domain.exif.longitude,
            altitude = domain.exif.altitude,
            capturedAt = domain.capturedAt,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt,
            assetTag = domain.assetTag
        )
    }
}
