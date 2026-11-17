package project.kosh.feature.gallery.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Compact translucent badge positioned at the bottom-start corner of video tiles
 * indicating video playback capability.
 *
 * @param modifier Composable modifier applied to the badge container.
 */
@Composable
fun VideoDurationBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color.Black.copy(alpha = 0.6f),
        modifier = modifier.padding(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// Previews

@Preview(name = "Video Duration Badge Light", showBackground = true)
@Composable
private fun VideoDurationBadgeLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        VideoDurationBadge()
    }
}

@Preview(name = "Video Duration Badge Dark", showBackground = true)
@Composable
private fun VideoDurationBadgeDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        VideoDurationBadge()
    }
}
