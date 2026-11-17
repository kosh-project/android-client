package project.kosh.feature.gallery.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import project.kosh.feature.gallery.loader.EncryptedThumbnailLoader
import project.kosh.feature.gallery.model.MediaFolder

/**
 * Layered thumbnail stack previewing up to three recent media assets from an album.
 *
 * Cards are fanned out with subtle Z-axis rotation angles (-6 deg and +4 deg) and tiered
 * elevation shadows to create tangible physical depth without visual clutter.
 *
 * @param folder Target media album containing preview URIs.
 * @param modifier Composable modifier applied to the root stack box.
 * @param thumbnailLoader Optional image pipeline loader for cached bitmap resolution.
 */
@Composable
fun AlbumThumbnailStack(
    folder: MediaFolder,
    modifier: Modifier = Modifier,
    thumbnailLoader: EncryptedThumbnailLoader? = null
) {
    AlbumThumbnailStack(
        previewUris = folder.previewUris,
        modifier = modifier,
        thumbnailLoader = thumbnailLoader
    )
}

/**
 * Layered thumbnail stack previewing up to three image URIs.
 *
 * @param previewUris List of media URIs to display in the fan stack.
 * @param modifier Composable modifier applied to the root stack box.
 * @param thumbnailLoader Optional image pipeline loader for cached bitmap resolution.
 */
@Composable
fun AlbumThumbnailStack(
    previewUris: List<Uri>,
    modifier: Modifier = Modifier,
    thumbnailLoader: EncryptedThumbnailLoader? = null
) {
    if (previewUris.isEmpty()) {
        EmptyStackPlaceholder(modifier = modifier)
        return
    }

    val items = previewUris.take(3)

    Box(
        modifier = modifier.size(48.dp),
        contentAlignment = Alignment.Center
    ) {
        // 3rd item at -6 deg rotation (bottom of stack)
        if (items.size >= 3) {
            StackedThumbnailCard(
                uri = items[2],
                loader = thumbnailLoader,
                rotationDegrees = -6f,
                offsetX = (-2).dp,
                offsetY = 1.dp,
                elevation = 1.dp
            )
        }

        // 2nd item at 4 deg rotation (middle of stack)
        if (items.size >= 2) {
            StackedThumbnailCard(
                uri = items[1],
                loader = thumbnailLoader,
                rotationDegrees = 4f,
                offsetX = 2.dp,
                offsetY = (-1).dp,
                elevation = 2.dp
            )
        }

        // Top 1st item with 8dp rounded corners, crisp border, and highest elevation
        StackedThumbnailCard(
            uri = items[0],
            loader = thumbnailLoader,
            rotationDegrees = 0f,
            offsetX = 0.dp,
            offsetY = 0.dp,
            elevation = 4.dp,
            hasBorder = true
        )
    }
}

@Composable
private fun EmptyStackPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun StackedThumbnailCard(
    uri: Uri,
    loader: EncryptedThumbnailLoader?,
    rotationDegrees: Float,
    offsetX: Dp = 0.dp,
    offsetY: Dp = 0.dp,
    elevation: Dp,
    hasBorder: Boolean = false,
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
                    loader.loadLocalUriPreview(context.contentResolver, uri)
                } else {
                    SubsampledImageDecoder.decode(context.contentResolver, uri, targetSize = 180)
                }
            }
            bitmap = loaded
        }
    }

    Surface(
        modifier = modifier
            .size(40.dp)
            .graphicsLayer {
                this.rotationZ = rotationDegrees
                this.translationX = offsetX.toPx()
                this.translationY = offsetY.toPx()
            },
        shape = RoundedCornerShape(8.dp),
        shadowElevation = elevation,
        border = if (hasBorder) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        },
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

// Previews

@Preview(name = "Album Stack Empty", showBackground = true)
@Composable
private fun AlbumThumbnailStackEmptyPreview() {
    GalleryPreviewTheme {
        AlbumThumbnailStack(previewUris = emptyList())
    }
}
