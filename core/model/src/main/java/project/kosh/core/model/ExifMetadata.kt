package project.kosh.core.model

import kotlinx.serialization.Serializable

/**
 * EXIF metadata extracted from media assets (camera properties, GPS geolocation, lens details).
 * Follows Google Photos style asset specification.
 */
@Serializable
data class ExifMetadata(
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val lensModel: String? = null,
    val focalLength: Double? = null,
    val aperture: Double? = null,
    val iso: Int? = null,
    val exposureTime: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null
)
