package project.kosh.feature.gallery

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import project.kosh.core.auth.AuthRepository
import project.kosh.core.model.AssetTag
import project.kosh.core.model.NetworkResult
import project.kosh.core.network.AssetClient
import javax.inject.Inject

/**
 * UI State describing active media upload progression.
 */
sealed interface UploadUiState {
    data object Idle : UploadUiState
    data class Uploading(val fileName: String) : UploadUiState
    data class Success(val fileName: String, val message: String = "Uploaded to vault") : UploadUiState
    data class Error(val message: String) : UploadUiState
}

/**
 * ViewModel managing gallery media selection, streaming uploads, session status, and user logout.
 *
 * Coordinates with [AssetClient] to stream picked photos to the backend CAS vault on [Dispatchers.IO],
 * tracking upload progress via [uploadState] and providing credential clearing via [logout].
 *
 * @param assetClient Network client managing authenticated binary asset transfers.
 * @param authRepository Domain repository managing active session token and vault key lifecycle.
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val assetClient: AssetClient,
    private val authRepository: AuthRepository
) : ViewModel() {

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _uploadState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uploadState: StateFlow<UploadUiState> = _uploadState.asStateFlow()

    private val _uploadStatus = MutableStateFlow("Idle")

    /**
     * Observable stream describing the current upload status or result message string.
     */
    val uploadStatus: StateFlow<String> = _uploadStatus.asStateFlow()

    private val _isSessionActive = MutableStateFlow(authRepository.isLoggedIn())
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _activeUser = MutableStateFlow(authRepository.getActiveUsername())
    val activeUser: StateFlow<String?> = _activeUser.asStateFlow()

    /**
     * Returns true if active session token and Master Vault Key are currently unlocked.
     */
    fun isSessionActive(): Boolean = authRepository.isLoggedIn()

    /**
     * Clears active authentication tokens and the Master Vault Key from hardware-encrypted storage.
     */
    fun logout() {
        authRepository.logout()
        _isSessionActive.value = false
        _activeUser.value = null
    }

    /**
     * Resets the active upload progress state back to [UploadUiState.Idle].
     */
    fun dismissUploadState() {
        _uploadState.value = UploadUiState.Idle
        _uploadStatus.value = "Idle"
    }

    /**
     * Initiates zero-copy streaming upload of a selected photo asset.
     *
     * Resolves the filename via [queryName] and dispatches the upload stream onto [Dispatchers.IO]
     * targeting the [AssetTag.GALLERY_ITEM] storage category.
     *
     * @param contentResolver The Android resolver used to inspect media metadata and open stream descriptors.
     * @param uri The content [Uri] referencing the chosen media asset.
     */
    fun uploadPhoto(contentResolver: ContentResolver, uri: Uri) {
        val fileName = queryName(contentResolver, uri) ?: "vault_item_${System.currentTimeMillis()}.jpg"

        viewModelScope.launch {
            _uploadState.value = UploadUiState.Uploading(fileName)
            _uploadStatus.value = "Uploading $fileName..."

            val result = withContext(ioDispatcher) {
                assetClient.uploadAsset(contentResolver, uri, AssetTag.GALLERY_ITEM, fileName)
            }

            when (result) {
                is NetworkResult.Success -> {
                    _uploadState.value = UploadUiState.Success(fileName, "Stored in vault (201 Created)")
                    _uploadStatus.value = "Success: 201 Created!"
                }
                is NetworkResult.Http -> {
                    val err = "Server error ${result.code}: ${result.serverMessage}"
                    _uploadState.value = UploadUiState.Error(err)
                    _uploadStatus.value = err
                }
                is NetworkResult.Failure -> {
                    val err = "Network error: ${result.userMessage}"
                    _uploadState.value = UploadUiState.Error(err)
                    _uploadStatus.value = err
                }
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