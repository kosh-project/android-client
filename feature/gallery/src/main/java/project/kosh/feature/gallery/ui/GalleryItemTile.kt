package project.kosh.feature.gallery.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.model.UploadStage
import project.kosh.core.model.UploadStatus

/**
 * High-performance 1:1 square media tile rendering a zero-knowledge thumbnail preview
 * with dual-phase upload progress animation.
 *
 * Visual Stages:
 * 1. Phase 1 (Hashing): 38% black veil shrinking *upwards* towards the top edge.
 * 2. Phase 2 (Upload): 38% black rectangle rising *from bottom* towards the top edge.
 * 3. Phase 3 (Done): Smooth fade-out reveal upon upload completion.
 */
@Composable
fun GalleryItemTile(
    item: MediaWithCryptoDetails,
    thumbnail: Bitmap?,
    uploadStage: UploadStage?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCompletedFlash by remember { mutableStateOf(false) }

    LaunchedEffect(uploadStage) {
        if (uploadStage is UploadStage.Completed) {
            showCompletedFlash = true
            delay(600L)
            showCompletedFlash = false
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    ) {
        // Base: Decoded Thumbnail or Shimmer Placeholder
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = item.asset.fileId,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center)
                )
            }
        }

        // Phase 1: Hashing Veil (38% opacity, shrinks UPWARDS to top)
        val isHashing = uploadStage is UploadStage.Hashing
        val hashProgress = (uploadStage as? UploadStage.Hashing)?.progress ?: 1f
        val animatedHashHeight by animateFloatAsState(
            targetValue = if (isHashing) (1f - hashProgress).coerceIn(0f, 1f) else 0f,
            animationSpec = tween(durationMillis = 200),
            label = "hashingVeilHeight"
        )

        if (animatedHashHeight > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedHashHeight)
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.38f))
            )
        }

        // Phase 2: Upload Veil (38% opacity, rises FROM BOTTOM to 100%)
        val isUploading = (uploadStage is UploadStage.Uploading || uploadStage is UploadStage.Finalizing) && !showCompletedFlash
        val uploadProgress = (uploadStage as? UploadStage.Uploading)?.progress ?: if (uploadStage is UploadStage.Finalizing) 1f else 0f
        val animatedUploadHeight by animateFloatAsState(
            targetValue = if (isUploading) uploadProgress.coerceIn(0f, 1f) else 0f,
            animationSpec = tween(durationMillis = 200),
            label = "uploadVeilHeight"
        )

        if (animatedUploadHeight > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedUploadHeight)
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.38f))
            )
        }

        // Phase 3: Completion Fade-Out
        AnimatedVisibility(
            visible = showCompletedFlash,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(400))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Synced",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Upload Status Badge (if still syncing in background)
        if (uploadStage != null && uploadStage !is UploadStage.Completed && !showCompletedFlash) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.50f),
                modifier = Modifier
                    .padding(6.dp)
                    .size(20.dp)
                    .align(Alignment.TopEnd)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (uploadStage is UploadStage.Failed) Icons.Default.Warning else Icons.Default.Sync,
                        contentDescription = if (uploadStage is UploadStage.Failed) "Upload Failed" else "Syncing",
                        tint = if (uploadStage is UploadStage.Failed) MaterialTheme.colorScheme.error else Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}
