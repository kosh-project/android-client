package project.kosh.feature.gallery.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import project.kosh.feature.gallery.loader.EncryptedThumbnailLoader
import project.kosh.feature.gallery.model.MediaFolder

/**
 * Interactive Modal Bottom Sheet allowing users to inspect detected on-device media folders
 * (e.g. Camera, WhatsApp, Screenshots) and toggle their automated vault sync enrollment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSelectionBottomSheet(
    folders: List<MediaFolder>,
    isLoading: Boolean,
    onSaveSelection: (selectedBucketIds: Set<String>) -> Unit,
    onDismiss: () -> Unit,
    thumbnailLoader: EncryptedThumbnailLoader? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedBuckets by remember(folders) {
        mutableStateOf(folders.filter { it.isSelectedForSync }.map { it.bucketId }.toSet())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Sync Media Folders",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Choose device albums to backup to your private node",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else if (folders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No media folders discovered on device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .height(280.dp)
                ) {
                    items(folders, key = { it.bucketId }) { folder ->
                        val isChecked = selectedBuckets.contains(folder.bucketId)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedBuckets = if (isChecked) {
                                        selectedBuckets - folder.bucketId
                                    } else {
                                        selectedBuckets + folder.bucketId
                                    }
                                }
                                .padding(vertical = 12.dp)
                        ) {
                            AlbumThumbnailStack(
                                folder = folder,
                                thumbnailLoader = thumbnailLoader
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = folder.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${folder.mediaCount} items",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedBuckets = if (checked) {
                                        selectedBuckets + folder.bucketId
                                    } else {
                                        selectedBuckets - folder.bucketId
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    onSaveSelection(selectedBuckets)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (selectedBuckets.isEmpty()) "Save Selection (None)" else "Start Syncing (${selectedBuckets.size} Folders)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

/**
 * Interactive stacked media thumbnails previewing up to 3 latest photos from an album,
 * with layered card rotations and elevation, or a clean folder icon placeholder when empty.
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

@Composable
fun AlbumThumbnailStack(
    previewUris: List<Uri>,
    modifier: Modifier = Modifier,
    thumbnailLoader: EncryptedThumbnailLoader? = null
) {
    if (previewUris.isEmpty()) {
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

        // Top 1st item on top with 8dp rounded corners, border, and elevation
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
                    decodeSubsampledFallback(context.contentResolver, uri, targetSize = 180)
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

private fun decodeSubsampledFallback(
    contentResolver: android.content.ContentResolver,
    uri: Uri,
    targetSize: Int
): Bitmap? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        val origWidth = boundsOptions.outWidth
        val origHeight = boundsOptions.outHeight
        if (origWidth <= 0 || origHeight <= 0) return null

        var inSampleSize = 1
        val maxDim = maxOf(origWidth, origHeight)
        while (maxDim / (inSampleSize * 2) >= targetSize) {
            inSampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    } catch (_: Exception) {
        null
    }
}
