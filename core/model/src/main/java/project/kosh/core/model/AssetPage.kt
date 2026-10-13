package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Paginated result envelope returned by GET /api/v1/assets.
 *
 * @property assets List of metadata rows fetched for the current page.
 * @property nextCursor Cursor boundary to retrieve the next page, or null if on the terminal page.
 */
@Serializable
data class AssetPage(
    val assets: List<AssetMetadataRow> = emptyList(),

    @SerialName("next_cursor")
    val nextCursor: AssetCursor? = null

) {
    /**
     * Indicates whether additional pages can be fetched from the remote server
     */
    val hasMore: Boolean get() = nextCursor != null
}
