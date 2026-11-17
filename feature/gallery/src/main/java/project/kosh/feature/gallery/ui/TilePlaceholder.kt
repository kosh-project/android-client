package project.kosh.feature.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Placeholder surface rendered when a media thumbnail bitmap is loading or resolving.
 * Displays an indeterminate progress indicator during remote downloads or a static icon otherwise.
 *
 * @param isVideo Whether the media asset is a video format.
 * @param isSyncingRemote Whether this thumbnail is actively downloading from the remote vault.
 * @param modifier Composable modifier applied to the placeholder box.
 */
@Composable
fun TilePlaceholder(
    isVideo: Boolean,
    isSyncingRemote: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (isSyncingRemote) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = if (isVideo) Icons.Default.PlayArrow else Icons.Default.Image,
                contentDescription = if (isVideo) "Video" else "Image",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// Previews

@Preview(name = "Image Placeholder Light", showBackground = true)
@Composable
private fun TilePlaceholderImageLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        TilePlaceholder(isVideo = false, isSyncingRemote = false, modifier = Modifier.size(100.dp))
    }
}

@Preview(name = "Remote Syncing Placeholder Dark", showBackground = true)
@Composable
private fun TilePlaceholderSyncingDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        TilePlaceholder(isVideo = true, isSyncingRemote = true, modifier = Modifier.size(100.dp))
    }
}
