package project.kosh.feature.gallery.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import project.kosh.feature.gallery.model.BatchUploadState

/**
 * Backwards-compatible alias for [BatchUploadFloatingBar].
 *
 * @param batchState Current snapshot of the multi-file batch upload progress.
 * @param onDismiss Invoked when the user taps the close button to dismiss the banner.
 * @param modifier Composable modifier applied to the outer layout container.
 */
@Composable
fun FloatingUploadBar(
    batchState: BatchUploadState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BatchUploadFloatingBar(
        batchState = batchState,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
