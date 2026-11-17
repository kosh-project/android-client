package project.kosh.feature.gallery.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.feature.gallery.model.GalleryDateGroup

private val GridItemPlacementSpec = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow
)

/**
 * Dynamic photo & video grid supporting multi-touch pinch zoom (1 to 5 columns),
 * smooth wheel scroll interpolation, sticky date headers, and animated item transitions.
 *
 * @param groups Date-partitioned groups of media assets.
 * @param gridState Scroll state for the lazy vertical grid.
 * @param thumbnailProvider Image pipeline provider for resolving encrypted thumbnails.
 * @param onItemClick Invoked when the user taps an individual media tile.
 * @param modifier Composable modifier applied to the root grid container.
 */
@Composable
fun GalleryMediaGrid(
    groups: List<GalleryDateGroup>,
    gridState: LazyGridState,
    thumbnailProvider: GalleryThumbnailProvider,
    onItemClick: (MediaWithCryptoDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    var columnCount by remember { mutableIntStateOf(4) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        state = gridState,
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 0.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .fillMaxSize()
            .smoothWheelScroll(gridState = gridState)
            .pinchZoomGridColumns(
                columnCount = columnCount,
                minColumns = 1,
                maxColumns = 5,
                onColumnsChange = { newColumns -> columnCount = newColumns }
            )
    ) {
        groups.forEach { group ->
            stickyHeader(
                key = "header_${group.key}"
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 16.dp, end = 96.dp, top = 8.dp, bottom = 8.dp)
                    )
                }
            }

            items(
                items = group.items,
                key = { it.asset.fileId },
                contentType = { if (it.asset.mediaType.startsWith("video/", ignoreCase = true)) "video" else "photo" }
            ) { item ->
                GalleryGridTileItem(
                    item = item,
                    thumbnailProvider = thumbnailProvider,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.animateItem(
                        placementSpec = GridItemPlacementSpec
                    )
                )
            }
        }
    }
}

/**
 * Encapsulated grid cell managing thumbnail resolution, 32ms debounce cancellation,
 * remote sync loading state, and upload stage subscriptions.
 */
@Composable
private fun GalleryGridTileItem(
    item: MediaWithCryptoDetails,
    thumbnailProvider: GalleryThumbnailProvider,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cachedBitmap = remember(item.asset.fileId) { thumbnailProvider.getFromMemoryCache(item) }
    var thumbnailBitmap by remember(item.asset.fileId) { mutableStateOf(cachedBitmap) }
    var isSyncingRemote by remember(item.asset.fileId) { mutableStateOf(false) }

    if (thumbnailBitmap == null) {
        LaunchedEffect(item.asset.fileId) {
            // 32ms debounce: aborts immediately on quick flings before touching disk or allocation
            delay(32L)
            val needsRemoteDownload = !thumbnailProvider.hasDiskCache(item.asset.fileId) &&
                !thumbnailProvider.isInFlight(item.asset.fileId) &&
                (item.cryptoMapping?.thumbnailCiphertextHash != null || item.asset.thumbnailHash != null)

            if (needsRemoteDownload) {
                isSyncingRemote = true
            }
            try {
                thumbnailBitmap = thumbnailProvider.loadThumbnail(item)
            } finally {
                isSyncingRemote = false
            }
        }
    }

    val uploadStage by remember(item.asset.fileId) {
        thumbnailProvider.getUploadStage(item.asset.fileId)
    }.collectAsState(initial = null)

    GalleryItemTile(
        item = item,
        thumbnail = thumbnailBitmap,
        isSyncingRemote = isSyncingRemote,
        uploadStage = uploadStage,
        onClick = onClick,
        modifier = modifier
    )
}

// Previews

@Preview(name = "Media Grid Light", showBackground = true)
@Composable
private fun GalleryMediaGridLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        GalleryMediaGrid(
            groups = listOf(
                GalleryDateGroup(
                    title = "Today",
                    key = "2026-11-17",
                    items = listOf(
                        MediaWithCryptoDetails(asset = MediaAssetEntity(fileId = "1", mediaType = "image/jpeg", fileSize = 1000L, capturedAt = 1700000000000L), cryptoMapping = null),
                        MediaWithCryptoDetails(asset = MediaAssetEntity(fileId = "2", mediaType = "video/mp4", fileSize = 2000L, capturedAt = 1700000000000L), cryptoMapping = null),
                        MediaWithCryptoDetails(asset = MediaAssetEntity(fileId = "3", mediaType = "image/png", fileSize = 3000L, capturedAt = 1700000000000L), cryptoMapping = null)
                    )
                )
            ),
            gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
            thumbnailProvider = PreviewGalleryThumbnailProvider,
            onItemClick = {}
        )
    }
}

@Preview(name = "Media Grid Dark", showBackground = true)
@Composable
private fun GalleryMediaGridDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        GalleryMediaGrid(
            groups = listOf(
                GalleryDateGroup(
                    title = "November 2026",
                    key = "2026-11-01",
                    items = listOf(
                        MediaWithCryptoDetails(asset = MediaAssetEntity(fileId = "4", mediaType = "image/jpeg", fileSize = 4000L, capturedAt = 1700000000000L), cryptoMapping = null)
                    )
                )
            ),
            gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
            thumbnailProvider = PreviewGalleryThumbnailProvider,
            onItemClick = {}
        )
    }
}
