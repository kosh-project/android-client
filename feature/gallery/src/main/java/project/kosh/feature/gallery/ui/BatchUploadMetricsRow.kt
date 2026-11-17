package project.kosh.feature.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import project.kosh.feature.gallery.model.BatchUploadState

/**
 * Linear progress bar and tabular byte transfer metrics for the floating upload bar.
 *
 * @param animatedProgress Continuously animated progress fraction between 0.0f and 1.0f.
 * @param batchState Current snapshot of multi-file batch upload progress.
 * @param modifier Composable modifier applied to the container.
 */
@Composable
fun BatchUploadMetricsRow(
    animatedProgress: Float,
    batchState: BatchUploadState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(10.dp))

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        if (batchState.totalBatchBytes > 0L) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val currentBytes = (animatedProgress * batchState.totalBatchBytes).toLong()
                    .coerceIn(0L, batchState.totalBatchBytes)
                Text(
                    text = "${formatBytes(currentBytes)} of ${formatBytes(batchState.totalBatchBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${batchState.completedItems}/${batchState.totalItems} files",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// Previews

@Preview(name = "Metrics Row Light", showBackground = true)
@Composable
private fun BatchUploadMetricsRowLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        BatchUploadMetricsRow(
            animatedProgress = 0.65f,
            batchState = BatchUploadState(
                isUploading = true,
                totalItems = 10,
                completedItems = 6,
                totalBatchBytes = 25_000_000L
            )
        )
    }
}

@Preview(name = "Metrics Row Dark", showBackground = true)
@Composable
private fun BatchUploadMetricsRowDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        BatchUploadMetricsRow(
            animatedProgress = 0.35f,
            batchState = BatchUploadState(
                isUploading = true,
                totalItems = 4,
                completedItems = 1,
                totalBatchBytes = 10_000_000L
            )
        )
    }
}
