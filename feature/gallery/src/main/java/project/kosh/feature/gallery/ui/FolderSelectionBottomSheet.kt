package project.kosh.feature.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import project.kosh.feature.gallery.loader.EncryptedThumbnailLoader
import project.kosh.feature.gallery.model.MediaFolder

/**
 * Interactive Modal Bottom Sheet allowing users to inspect discovered on-device media
 * albums and select which folders sync automatically to their private zero-knowledge vault.
 *
 * @param folders List of device folders discovered via MediaStore query.
 * @param isLoading Whether the folder discovery scanner is actively running in background.
 * @param onSaveSelection Invoked with the set of selected bucket IDs when saving.
 * @param onDismiss Invoked when the sheet is dismissed without saving.
 * @param thumbnailLoader Optional image pipeline loader for cached preview resolution.
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
        sheetState = sheetState,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        FolderSelectionSheetContent(
            folders = folders,
            selectedBuckets = selectedBuckets,
            isLoading = isLoading,
            onToggleFolder = { bucketId ->
                selectedBuckets = if (selectedBuckets.contains(bucketId)) {
                    selectedBuckets - bucketId
                } else {
                    selectedBuckets + bucketId
                }
            },
            onSave = {
                onSaveSelection(selectedBuckets)
                onDismiss()
            },
            thumbnailLoader = thumbnailLoader
        )
    }
}

@Composable
internal fun FolderSelectionSheetContent(
    folders: List<MediaFolder>,
    selectedBuckets: Set<String>,
    isLoading: Boolean,
    onToggleFolder: (String) -> Unit,
    onSave: () -> Unit,
    thumbnailLoader: EncryptedThumbnailLoader?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
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
                    text = "Sync media folders",
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

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        } else if (folders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No media folders discovered on device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(folders, key = { it.bucketId }) { folder ->
                    val isChecked = selectedBuckets.contains(folder.bucketId)
                    FolderGridCard(
                        folder = folder,
                        isChecked = isChecked,
                        thumbnailLoader = thumbnailLoader,
                        onToggle = { onToggleFolder(folder.bucketId) }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (selectedBuckets.isEmpty()) "Save selection (none)" else "Start syncing (${selectedBuckets.size} folders)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    }
}

// Previews

@Preview(name = "Folder Selection Content Light", showBackground = true)
@Composable
private fun FolderSelectionContentLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        FolderSelectionSheetContent(
            folders = listOf(
                MediaFolder(bucketId = "1", displayName = "Camera", mediaCount = 84, representativeUri = null, previewUris = emptyList(), isSelectedForSync = true),
                MediaFolder(bucketId = "2", displayName = "Screenshots", mediaCount = 22, representativeUri = null, previewUris = emptyList(), isSelectedForSync = false),
                MediaFolder(bucketId = "3", displayName = "WhatsApp", mediaCount = 153, representativeUri = null, previewUris = emptyList(), isSelectedForSync = true)
            ),
            selectedBuckets = setOf("1", "3"),
            isLoading = false,
            onToggleFolder = {},
            onSave = {},
            thumbnailLoader = null
        )
    }
}

@Preview(name = "Folder Selection Loading Dark", showBackground = true)
@Composable
private fun FolderSelectionLoadingDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        FolderSelectionSheetContent(
            folders = emptyList(),
            selectedBuckets = emptySet(),
            isLoading = true,
            onToggleFolder = {},
            onSave = {},
            thumbnailLoader = null
        )
    }
}
