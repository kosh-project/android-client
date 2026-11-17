package project.kosh.feature.gallery.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.model.UploadStage

/**
 * High-performance 1:1 square media tile rendering an encrypted thumbnail preview
 * with dual-phase upload progression and completion reveals.
 *
 * ### Dual-Phase Upload Progression
 * When syncing files to the vault:
 * - **Phase 1 (Hashing):** A 38% black veil shrinks upwards to the top edge as SHA-256 chunk
 *   hashing executes.
 * - **Phase 2 (Uploading):** A 38% black veil rises from the bottom edge to the top edge in sync
 *   with wire byte transfer progress.
 * - **Phase 3 (Completion Reveal):** A checkmark badge flashes with an animated fade-out reveal.
 *
 * @param item Metadata and cryptographic details for the media asset.
 * @param thumbnail Decoded GPU-backed hardware [Bitmap] thumbnail, or null if loading.
 * @param uploadStage Current stage of the upload pipeline (e.g. Hashing, Uploading, Completed).
 * @param onClick Invoked when the user taps the media tile.
 * @param modifier Composable modifier applied to the outer tile container.
 * @param isLoading Whether the thumbnail decoding pipeline is actively busy.
 * @param isSyncingRemote Whether this thumbnail is currently downloading from the remote server.
 */
@Composable
fun GalleryItemTile(
    item: MediaWithCryptoDetails,
    thumbnail: Bitmap?,
    uploadStage: UploadStage?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isSyncingRemote: Boolean = false
) {
    var showCompletedFlash by remember { mutableStateOf(false) }

    LaunchedEffect(uploadStage) {
        if (uploadStage is UploadStage.Completed) {
            showCompletedFlash = true
            try {
                delay(GalleryItemTileVisuals.COMPLETED_FLASH_DURATION_MS)
            } finally {
                showCompletedFlash = false
            }
        } else {
            showCompletedFlash = false
        }
    }

    val isVideo = item.asset.mediaType.startsWith("video/", ignoreCase = true)
    val imageBitmap = remember(thumbnail) { thumbnail?.asImageBitmap() }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    ) {
        if (imageBitmap == null) {
            TilePlaceholder(isVideo = isVideo, isSyncingRemote = isSyncingRemote)
        } else {
            Image(
                bitmap = imageBitmap,
                contentDescription = item.asset.fileId,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (isVideo) {
                VideoDurationBadge(modifier = Modifier.align(Alignment.BottomStart))
            }
        }

        UploadVeilOverlay(uploadStage = uploadStage, showCompletedFlash = showCompletedFlash)

        UploadCompletionFlash(showCompletedFlash = showCompletedFlash)

        UploadStatusBadge(
            uploadStage = uploadStage,
            showCompletedFlash = showCompletedFlash,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

// Previews

@Preview(name = "Image Placeholder Light", showBackground = true)
@Composable
private fun GalleryItemTileImagePlaceholderPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        GalleryItemTile(
            item = MediaWithCryptoDetails(
                asset = MediaAssetEntity(fileId = "sample-1", mediaType = "image/jpeg", fileSize = 1024L, capturedAt = 1700000000000L),
                cryptoMapping = null
            ),
            thumbnail = null,
            uploadStage = null,
            onClick = {},
            modifier = Modifier.size(120.dp)
        )
    }
}

@Preview(name = "Video Tile Dark", showBackground = true)
@Composable
private fun GalleryItemTileVideoPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        GalleryItemTile(
            item = MediaWithCryptoDetails(
                asset = MediaAssetEntity(fileId = "sample-video", mediaType = "video/mp4", fileSize = 2048000L, capturedAt = 1700000000000L),
                cryptoMapping = null
            ),
            thumbnail = null,
            uploadStage = null,
            onClick = {},
            modifier = Modifier.size(120.dp)
        )
    }
}

@Preview(name = "Uploading Phase 2 Dark", showBackground = true)
@Composable
private fun GalleryItemTileUploadingPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        GalleryItemTile(
            item = MediaWithCryptoDetails(
                asset = MediaAssetEntity(fileId = "sample-uploading", mediaType = "image/png", fileSize = 512000L, capturedAt = 1700000000000L),
                cryptoMapping = null
            ),
            thumbnail = null,
            uploadStage = UploadStage.Uploading(progress = 0.65f, bytesSent = 332800L, totalBytes = 512000L),
            onClick = {},
            modifier = Modifier.size(120.dp)
        )
    }
}
