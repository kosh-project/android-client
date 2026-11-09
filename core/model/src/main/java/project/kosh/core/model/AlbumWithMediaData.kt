package project.kosh.core.model

import kotlinx.serialization.Serializable

/**
 * Composite domain model containing an album, its cover asset, and its associated media assets.
 */
@Serializable
data class AlbumWithMediaData(
    val album: Album,
    val mediaAssets: List<MediaAsset> = emptyList(),
    val coverAsset: MediaAsset? = null
)
