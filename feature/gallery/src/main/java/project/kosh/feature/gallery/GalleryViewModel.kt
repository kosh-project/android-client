package project.kosh.feature.gallery

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import project.kosh.core.model.AssetTag
import project.kosh.core.model.NetworkResult
import project.kosh.core.network.AssetClient
import project.kosh.core.network.NetworkModule
import javax.inject.Inject

/**
 * ViewModel managing gallery media selection, streaming uploads, and transfer status reporting.
 *
 * Coordinates with [AssetClient] to stream picked photos to the backend CAS vault on [Dispatchers.IO],
 * tracking upload progress via the reactive [uploadStatus] state flow.
 *
 * @param assetClient Network client managing authenticated binary asset transfers.
 */
@HiltViewModel
class GalleryViewModel  @Inject constructor(
    private val assetClient: AssetClient
) : ViewModel() {
    private val _uploadStatus = MutableStateFlow("Idle")

    /**
     * Observable stream describing the current upload status or result message.
     */
    val uploadStatus = _uploadStatus.asStateFlow()

    /**
     * Initiates zero-copy streaming upload of a selected photo asset.
     *
     * Resolves the filename via [queryName] and dispatches the upload stream onto [Dispatchers.IO]
     * targeting the [AssetTag.GALLERY_ITEM] storage category.
     *
     * @param contentResolver The Android resolver used to inspect media metadata and open stream descriptors.
     * @param uri The content [Uri] referencing the chosen media asset.
     */
    fun uploadPhoto(contentResolver: ContentResolver, uri : Uri) {
        viewModelScope.launch {
            _uploadStatus.value = "Uploading..."

            val result = withContext(Dispatchers.IO) {
                val fileName = queryName(contentResolver, uri) ?: "unknown_upload.jpg";

                assetClient.uploadAsset(contentResolver, uri, AssetTag.GALLERY_ITEM, fileName)
            }

            _uploadStatus.value = when (result) {
                is NetworkResult.Success -> "Success: 201 Created!"
                is NetworkResult.Http -> "Server Error ${result.code} - ${result.serverMessage}"
                is NetworkResult.Failure -> "Network Failure - ${result.userMessage}"
            }
        }
    }

    /**
     * Helper to resolve the user-facing display name of a media asset from its content [Uri].
     *
     * @param resolver The active content resolver.
     * @param uri The content URI to inspect.
     * @return The resolved display name string, or `null` if the cursor query returned no match.
     */
    private fun queryName(resolver: ContentResolver, uri: Uri): String? {
        resolver.query(uri, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }
}