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

@HiltViewModel
class GalleryViewModel  @Inject constructor(
    private val assetClient: AssetClient
) : ViewModel() {
    private val _uploadStatus = MutableStateFlow("Idle")
    val uploadStatus = _uploadStatus.asStateFlow()

    fun uploadPhoto(contentResolver: ContentResolver, uri : Uri) {
        viewModelScope.launch {
            _uploadStatus.value = "Uploading..."

            val result = withContext(Dispatchers.IO) {
                val fileName = queryName(contentResolver, uri) ?: "unknown_upload.jpg";

                assetClient.uploadAsset(contentResolver, uri, AssetTag.GALLERY_ITEM, fileName)
            }

            _uploadStatus.value = when (result) {
                is NetworkResult.Success -> "Success: 201 Created!"
                is NetworkResult.HttpError -> "Server Error ${result.code} - ${result.serverMessage}"
                is NetworkResult.NetworkError -> "Network Failure - ${result.exception.message}"
            }
        }
    }

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