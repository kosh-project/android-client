package project.kosh.feature.gallery.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import project.kosh.feature.gallery.loader.EncryptedThumbnailLoader
import project.kosh.feature.gallery.model.MediaFolder

/**
 * Grid album tile rendering a large photo preview, album title, item count,
 * and selection checkmark badge.
 *
 * @param folder The media album represented by this card.
 * @param isChecked Whether this album is currently selected for automated background sync.
 * @param thumbnailLoader Optional image pipeline loader for cached preview resolution.
 * @param onToggle Invoked when the user taps the card to select or deselect the folder.
 * @param modifier Composable modifier applied to the outer surface.
 */
@Composable
fun FolderGridCard(
    folder: MediaFolder,
    isChecked: Boolean,
    thumbnailLoader: EncryptedThumbnailLoader?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = if (isChecked) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (isChecked) 1.5.dp else 1.dp,
            color = if (isChecked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            }
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (folder.previewUris.isNotEmpty()) {
                    AlbumLargePreview(
                        uri = folder.previewUris.first(),
                        loader = thumbnailLoader
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = if (isChecked) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f),
                    border = BorderStroke(1.5.dp, if (isChecked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f)),
                    modifier = Modifier
                        .padding(6.dp)
                        .size(24.dp)
                        .align(Alignment.TopEnd)
                ) {
                    if (isChecked) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = "${folder.mediaCount} items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun AlbumLargePreview(
    uri: Uri,
    loader: EncryptedThumbnailLoader?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(uri, loader) {
        mutableStateOf(loader?.memoryCache?.get(uri.toString()))
    }

    LaunchedEffect(uri, loader) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                if (loader != null) {
                    loader.loadLocalUriPreview(context.contentResolver, uri, targetSize = 360)
                } else {
                    SubsampledImageDecoder.decode(context.contentResolver, uri, targetSize = 360)
                }
            }
            bitmap = loaded
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize()
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

// Previews

@Preview(name = "Folder Card Unselected Light", showBackground = true)
@Composable
private fun FolderGridCardUnselectedLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        FolderGridCard(
            folder = MediaFolder(
                bucketId = "camera-1",
                displayName = "Camera",
                mediaCount = 142,
                representativeUri = null,
                previewUris = emptyList(),
                isSelectedForSync = false
            ),
            isChecked = false,
            thumbnailLoader = null,
            onToggle = {}
        )
    }
}

@Preview(name = "Folder Card Selected Dark", showBackground = true)
@Composable
private fun FolderGridCardSelectedDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        FolderGridCard(
            folder = MediaFolder(
                bucketId = "whatsapp-1",
                displayName = "WhatsApp Images",
                mediaCount = 380,
                representativeUri = null,
                previewUris = emptyList(),
                isSelectedForSync = true
            ),
            isChecked = true,
            thumbnailLoader = null,
            onToggle = {}
        )
    }
}
