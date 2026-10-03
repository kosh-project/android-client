package project.kosh.feature.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * Primary Jetpack Compose screen providing media selection and CAS upload capabilities.
 *
 * Utilizes the modern AndroidX photo picker contract ([ActivityResultContracts.PickVisualMedia])
 * to grant scoped read access to individual user-selected images without requiring broad storage
 * runtime permissions (`READ_EXTERNAL_STORAGE` or `READ_MEDIA_IMAGES`).
 *
 * Upon media selection, passes the content URI to [GalleryViewModel.uploadPhoto] to trigger
 * zero-copy streaming upload to the remote Kosh storage vault.
 *
 * @param viewModel The Hilt-backed [GalleryViewModel] managing upload state and network transfer.
 */
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uploadState by viewModel.uploadStatus.collectAsState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadPhoto(context.contentResolver, uri)
        }
    }

    Column(
        Modifier.fillMaxSize(),
        Arrangement.Center,
        Alignment.CenterHorizontally
    ) {
        Text("Upload Prototype", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = {
            photoPickerLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }) {
            Text("Select Photo")
        }
        Spacer(Modifier.height(16.dp))

        Text(text = uploadState, color = MaterialTheme.colorScheme.primary)
    }
}