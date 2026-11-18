package project.kosh.feature.gallery.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.feature.gallery.model.BatchUploadState
import project.kosh.feature.gallery.model.GalleryDateGroup

/**
 * 100% Stateless presentation hierarchy for the Media Vault screen.
 *
 * Implements strict state hoisting (ViewModel-free) enabling immediate preview rendering
 * in Android Studio and isolated JVM Robolectric testing.
 *
 * @param groupedGalleryItems Date-partitioned groups of encrypted media assets.
 * @param isBatchUploading Whether a multi-file upload session is currently active.
 * @param batchUploadState Current snapshot of upload progression metrics.
 * @param isSyncing Whether a background delta-CRDT or pull-to-refresh sync is executing.
 * @param activeUser Display name or handle of the authenticated user.
 * @param isSessionActive Whether cryptographic session credentials remain active.
 * @param isPinging Whether a server reachability test ping is in progress.
 * @param thumbnailProvider Image pipeline interface for resolving thumbnails.
 * @param onLaunchPhotoPicker Invoked when the user triggers the media file selector.
 * @param onDismissBatchBar Invoked when dismissing the floating batch progress bar.
 * @param onRefreshSync Invoked when pulling down past threshold to request a vault sync.
 * @param onOpenFolderSelection Invoked to open device folder backup selection.
 * @param onStartPairing Invoked to begin device-to-device pairing.
 * @param onOpenServerConfig Invoked to navigate to remote server setup.
 * @param onPingServer Invoked to test remote server latency.
 * @param onLogoutRequest Invoked when the user requests account logout.
 * @param onItemClick Invoked when tapping an individual media asset tile.
 * @param modifier Composable modifier applied to the root scaffold.
 */
@Composable
fun GalleryScreenContent(
    groupedGalleryItems: List<GalleryDateGroup>,
    isBatchUploading: Boolean,
    batchUploadState: BatchUploadState,
    isSyncing: Boolean,
    activeUser: String?,
    isSessionActive: Boolean,
    isPinging: Boolean,
    thumbnailProvider: GalleryThumbnailProvider,
    onLaunchPhotoPicker: () -> Unit,
    onDismissBatchBar: () -> Unit,
    onRefreshSync: () -> Unit,
    onOpenFolderSelection: () -> Unit,
    onStartPairing: () -> Unit,
    onOpenServerConfig: () -> Unit,
    onPingServer: () -> Unit,
    onLogoutRequest: () -> Unit,
    onItemClick: (MediaWithCryptoDetails) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val scrollTracker = remember {
        GalleryScrollDirectionTracker(
            initialIndex = gridState.firstVisibleItemIndex,
            initialOffset = gridState.firstVisibleItemScrollOffset
        )
    }

    LaunchedEffect(gridState) {
        snapshotFlow {
            Triple(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
                gridState.isScrollInProgress
            )
        }.collect { (index, offset, inProgress) ->
            scrollTracker.onScrollUpdate(index, offset, inProgress)
        }
    }

    val isFabExpanded by remember {
        derivedStateOf {
            isGalleryFabExpanded(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        }
    }

    val isIslandVisible by remember {
        derivedStateOf {
            isGalleryIslandVisible(
                isScrollingDown = scrollTracker.isScrollingDown,
                showTopMenu = false,
                firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset
            )
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            GalleryFloatingActionButtonSection(
                isFabExpanded = isFabExpanded,
                batchUploadState = batchUploadState,
                onLaunchPicker = onLaunchPhotoPicker,
                onDismissBatchBar = onDismissBatchBar
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PullToRefreshScaffold(
                isRefreshing = isSyncing,
                onRefresh = onRefreshSync,
                isTopBarVisible = isIslandVisible
            ) {
                if (groupedGalleryItems.isEmpty() && !isBatchUploading) {
                    EmptyVaultView(
                        onSelectPhotos = onLaunchPhotoPicker,
                        onSyncFolders = onOpenFolderSelection
                    )
                } else {
                    GalleryMediaGrid(
                        groups = groupedGalleryItems,
                        gridState = gridState,
                        thumbnailProvider = thumbnailProvider,
                        onItemClick = onItemClick
                    )
                }
            }

            GalleryTopIslandBar(
                isVisible = isIslandVisible,
                activeUser = activeUser,
                isSessionActive = isSessionActive,
                isPinging = isPinging,
                onOpenFolderSelection = onOpenFolderSelection,
                onStartPairing = onStartPairing,
                onTriggerSync = onRefreshSync,
                onOpenServerConfig = onOpenServerConfig,
                onPingServer = onPingServer,
                onLogoutRequest = onLogoutRequest,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

// Previews

@Preview(name = "Populated Gallery Light", showBackground = true)
@Composable
private fun GalleryScreenPopulatedLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        GalleryScreenContent(
            groupedGalleryItems = listOf(
                GalleryDateGroup(
                    title = "Today",
                    key = "2026-11-17",
                    items = listOf(
                        MediaWithCryptoDetails(asset = MediaAssetEntity(fileId = "1", mediaType = "image/jpeg", fileSize = 1000L, capturedAt = 1700000000000L), cryptoMapping = null),
                        MediaWithCryptoDetails(asset = MediaAssetEntity(fileId = "2", mediaType = "video/mp4", fileSize = 2000L, capturedAt = 1700000000000L), cryptoMapping = null)
                    )
                )
            ),
            isBatchUploading = false,
            batchUploadState = BatchUploadState(),
            isSyncing = false,
            activeUser = "satoshi",
            isSessionActive = true,
            isPinging = false,
            thumbnailProvider = PreviewGalleryThumbnailProvider,
            onLaunchPhotoPicker = {},
            onDismissBatchBar = {},
            onRefreshSync = {},
            onOpenFolderSelection = {},
            onStartPairing = {},
            onOpenServerConfig = {},
            onPingServer = {},
            onLogoutRequest = {}
        )
    }
}

@Preview(name = "Empty Vault Dark", showBackground = true)
@Composable
private fun GalleryScreenEmptyDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        GalleryScreenContent(
            groupedGalleryItems = emptyList(),
            isBatchUploading = false,
            batchUploadState = BatchUploadState(),
            isSyncing = false,
            activeUser = "alice",
            isSessionActive = true,
            isPinging = false,
            thumbnailProvider = PreviewGalleryThumbnailProvider,
            onLaunchPhotoPicker = {},
            onDismissBatchBar = {},
            onRefreshSync = {},
            onOpenFolderSelection = {},
            onStartPairing = {},
            onOpenServerConfig = {},
            onPingServer = {},
            onLogoutRequest = {}
        )
    }
}
