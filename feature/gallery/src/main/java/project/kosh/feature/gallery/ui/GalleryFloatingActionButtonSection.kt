package project.kosh.feature.gallery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import project.kosh.feature.gallery.model.BatchUploadState

/**
 * Floating action section anchoring the Upload media FAB and the reactive batch upload progress bar.
 *
 * @param isFabExpanded Whether the FAB label is expanded (at top of gallery) or collapsed (scrolled down).
 * @param batchUploadState Reactive state snapshot of the active or idle batch upload pipeline.
 * @param onLaunchPicker Invoked when the user taps the Upload FAB to open the photo picker.
 * @param onDismissBatchBar Invoked when the user dismisses the floating upload progress bar.
 * @param modifier Composable modifier applied to the outer layout container.
 */
@Composable
fun GalleryFloatingActionButtonSection(
    isFabExpanded: Boolean,
    batchUploadState: BatchUploadState,
    onLaunchPicker: () -> Unit,
    onDismissBatchBar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        ExtendedFloatingActionButton(
            expanded = isFabExpanded,
            onClick = onLaunchPicker,
            icon = {
                Icon(Icons.Default.Add, contentDescription = "Upload media")
            },
            text = { Text("Upload", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) },
            shape = RoundedCornerShape(14.dp),
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 6.dp,
                pressedElevation = 8.dp,
                focusedElevation = 6.dp,
                hoveredElevation = 7.dp
            ),
            modifier = Modifier.padding(bottom = if (batchUploadState.isUploading) 10.dp else 0.dp)
        )

        BatchUploadFloatingBar(
            batchState = batchUploadState,
            onDismiss = onDismissBatchBar,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// Previews

@Preview(name = "FAB Section Expanded Light", showBackground = true)
@Composable
private fun GalleryFloatingActionButtonSectionExpandedLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        GalleryFloatingActionButtonSection(
            isFabExpanded = true,
            batchUploadState = BatchUploadState(),
            onLaunchPicker = {},
            onDismissBatchBar = {}
        )
    }
}

@Preview(name = "FAB Section Collapsed Dark", showBackground = true)
@Composable
private fun GalleryFloatingActionButtonSectionCollapsedDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        GalleryFloatingActionButtonSection(
            isFabExpanded = false,
            batchUploadState = BatchUploadState(),
            onLaunchPicker = {},
            onDismissBatchBar = {}
        )
    }
}
