package project.kosh.feature.gallery.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import project.kosh.core.model.UploadStage

/**
 * Animated dual-phase upload progression overlay.
 *
 * - **Phase 1 (Hashing):** A 38% black veil shrinks upwards to the top edge as SHA-256 chunk
 *   hashing executes.
 * - **Phase 2 (Uploading):** A 38% black veil rises from the bottom edge to the top edge in sync
 *   with wire byte transfer progress.
 *
 * @param uploadStage Current stage of the upload pipeline.
 * @param showCompletedFlash Whether the transient post-upload completion animation is active.
 * @param modifier Composable modifier applied to the overlay container.
 */
@Composable
fun UploadVeilOverlay(
    uploadStage: UploadStage?,
    showCompletedFlash: Boolean,
    modifier: Modifier = Modifier
) {
    // Phase 1: Hashing veil (shrinks upwards to top)
    val isHashing = uploadStage is UploadStage.Hashing
    val hashProgress = (uploadStage as? UploadStage.Hashing)?.progress ?: 1f
    val animatedHashHeight by animateFloatAsState(
        targetValue = if (isHashing) (1f - hashProgress).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "hashingVeilHeight"
    )

    if (animatedHashHeight > 0.01f) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(animatedHashHeight)
                .background(Color.Black.copy(alpha = GalleryItemTileVisuals.VEIL_ALPHA))
        )
    }

    // Phase 2: Upload veil (rises from bottom to top)
    val isCompleted = uploadStage is UploadStage.Completed || showCompletedFlash
    val isUploading = uploadStage is UploadStage.Uploading || uploadStage is UploadStage.Finalizing
    val uploadProgress = (uploadStage as? UploadStage.Uploading)?.progress
        ?: if (uploadStage is UploadStage.Finalizing || isCompleted) 1f else 0f

    val animatedVeilAlpha by animateFloatAsState(
        targetValue = GalleryItemTileVisuals.targetVeilAlpha(isUploading),
        animationSpec = tween(durationMillis = GalleryItemTileVisuals.VEIL_FADE_OUT_DURATION_MS),
        label = "uploadVeilAlpha"
    )

    val keepFullHeight = GalleryItemTileVisuals.shouldKeepFullHeight(isCompleted, animatedVeilAlpha)
    val animatedUploadHeight by animateFloatAsState(
        targetValue = GalleryItemTileVisuals.targetVeilHeight(isUploading, uploadProgress, keepFullHeight),
        animationSpec = tween(durationMillis = GalleryItemTileVisuals.VEIL_RISE_DURATION_MS),
        label = "uploadVeilHeight"
    )

    if (animatedUploadHeight > 0.01f && animatedVeilAlpha > 0.001f) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedUploadHeight)
                    .background(Color.Black.copy(alpha = animatedVeilAlpha))
            )
        }
    }
}

/**
 * Animated checkmark badge appearing upon successful completion of an asset upload.
 */
@Composable
fun UploadCompletionFlash(
    showCompletedFlash: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = showCompletedFlash,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(600)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
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
}

/**
 * Badge docked at the top-end corner indicating in-progress or failed upload states.
 */
@Composable
fun UploadStatusBadge(
    uploadStage: UploadStage?,
    showCompletedFlash: Boolean,
    modifier: Modifier = Modifier
) {
    if (uploadStage != null && uploadStage !is UploadStage.Completed && !showCompletedFlash) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.50f),
            modifier = modifier
                .padding(6.dp)
                .size(20.dp)
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

// Previews

@Preview(name = "Completion Flash Light", showBackground = true)
@Composable
private fun UploadCompletionFlashLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        Box(modifier = Modifier.size(100.dp)) {
            UploadCompletionFlash(showCompletedFlash = true)
        }
    }
}

@Preview(name = "Upload Status Badge Dark", showBackground = true)
@Composable
private fun UploadStatusBadgeDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        Box(modifier = Modifier.size(60.dp)) {
            UploadStatusBadge(
                uploadStage = UploadStage.Uploading(0.5f, 100L, 200L),
                showCompletedFlash = false
            )
        }
    }
}
