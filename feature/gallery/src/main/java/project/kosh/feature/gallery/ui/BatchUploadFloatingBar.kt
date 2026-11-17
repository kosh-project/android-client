package project.kosh.feature.gallery.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import project.kosh.core.model.UploadStage
import project.kosh.feature.gallery.model.BatchUploadState

/**
 * Floating batch upload progress card docked above the gallery grid.
 *
 * Displays real-time throttled chunk upload progress, current file name,
 * pipeline stage description, and aggregate byte transfer metrics.
 *
 * @param batchState Current snapshot of the multi-file batch upload progress.
 * @param onDismiss Invoked when the user taps the close button to dismiss the banner.
 * @param modifier Composable modifier applied to the outer layout container.
 */
@Composable
fun BatchUploadFloatingBar(
    batchState: BatchUploadState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = batchState.overallProgress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "batch_upload_progress"
    )

    AnimatedVisibility(
        visible = batchState.isUploading,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeOut(animationSpec = tween(250)),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { animatedProgress },
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(38.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = "Uploading",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    val percent = (animatedProgress * 100).toInt()
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (batchState.totalItems > 0) {
                                "${batchState.completedItems}/${batchState.totalItems} files uploaded ($percent%)"
                            } else {
                                "Uploading to vault ($percent%)"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${batchState.currentFileName ?: "Media file"} • ${batchState.stageDescription}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                BatchUploadMetricsRow(
                    animatedProgress = animatedProgress,
                    batchState = batchState
                )
            }
        }
    }
}

// Previews

@Preview(name = "Batch Uploading Light", showBackground = true)
@Composable
private fun BatchUploadFloatingBarUploadingLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        BatchUploadFloatingBar(
            batchState = BatchUploadState(
                isUploading = true,
                totalItems = 5,
                completedItems = 2,
                currentFileName = "IMG_20261117_120402.jpg",
                currentStage = UploadStage.Uploading(progress = 0.45f, bytesSent = 6_930_000L, totalBytes = 15_400_000L),
                totalBatchBytes = 15_400_000L
            ),
            onDismiss = {}
        )
    }
}

@Preview(name = "Batch Uploading Dark", showBackground = true)
@Composable
private fun BatchUploadFloatingBarUploadingDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        BatchUploadFloatingBar(
            batchState = BatchUploadState(
                isUploading = true,
                totalItems = 12,
                completedItems = 8,
                currentFileName = "VID_20261117_133000.mp4",
                currentStage = UploadStage.Encrypting,
                totalBatchBytes = 48_500_000L
            ),
            onDismiss = {}
        )
    }
}
