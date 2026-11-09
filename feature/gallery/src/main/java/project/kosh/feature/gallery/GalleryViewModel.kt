package project.kosh.feature.gallery

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import project.kosh.core.auth.AuthRepository
import project.kosh.core.auth.DevicePairingManager
import project.kosh.core.auth.PairingState
import project.kosh.core.model.ActivePairingSession
import project.kosh.core.model.AssetTag
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PairingQrPayload
import project.kosh.core.network.AssetClient
import kotlinx.serialization.json.Json
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
 * @param devicePairingManager E2EE device pairing manager for linking other devices.
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val assetClient: AssetClient,
    private val authRepository: AuthRepository,
    private val devicePairingManager: DevicePairingManager
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

    private val json = Json { ignoreUnknownKeys = true }

    private var pairingJob: Job? = null

    private val _pairingState = MutableStateFlow<PairingState?>(null)
    val pairingState: StateFlow<PairingState?> = _pairingState.asStateFlow()

    private val _activePairingSession = MutableStateFlow<ActivePairingSession?>(null)

    /**
     * Observable active pairing session containing mailbox ID, QR payload, and expiration.
     */
    val activePairingSession: StateFlow<ActivePairingSession?> = _activePairingSession.asStateFlow()

    private val _pairingSuccessMessage = MutableStateFlow<String?>(null)
    val pairingSuccessMessage: StateFlow<String?> = _pairingSuccessMessage.asStateFlow()

    /**
     * Starts Flow B hosting on this authenticated device to link other devices to the user's vault.
     */
    fun startHostPairing() {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            _pairingState.value = null
            _activePairingSession.value = null
            _pairingSuccessMessage.value = null
            try {
                devicePairingManager.startHostFlow().collect { pState ->
                    if (pState is PairingState.AwaitingJoin) {
                        _activePairingSession.value = ActivePairingSession(
                            mailboxId = pState.mailboxId,
                            qrPayloadJson = pState.qrPayloadJson,
                            expiresInSeconds = pState.expiresInSeconds
                        )
                    } else if (pState is PairingState.Ready) {
                        _activePairingSession.value = ActivePairingSession(
                            mailboxId = pState.mailboxId,
                            qrPayloadJson = pState.qrPayloadJson,
                            expiresInSeconds = pState.expiresInSeconds
                        )
                    }
                    _pairingState.value = pState
                    if (pState is PairingState.Success) {
                        _pairingSuccessMessage.value = "Device successfully linked!"
                    }
                }
            } catch (e: Exception) {
                _pairingState.value = PairingState.Error("Host pairing failed: ${e.message}", e)
            }
        }
    }

    /**
     * Fulfills a peer device's receiver session (Flow A) using its scanned QR code payload.
     */
    fun fulfillReceiverQr(qrJson: String) {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            try {
                val payload = json.decodeFromString<PairingQrPayload>(qrJson)
                val rxPubKey = payload.publicKeyHex.orEmpty()
                _pairingState.value = PairingState.AwaitingFulfillment
                val result = if (rxPubKey.isNotBlank()) {
                    devicePairingManager.fulfillSenderSession(
                        mailboxId = payload.mailboxId,
                        receiverPublicKeyHex = rxPubKey,
                        token = payload.token
                    )
                } else {
                    devicePairingManager.fulfillSenderWithCode(
                        mailboxId = payload.mailboxId,
                        token = payload.token
                    )
                }
                when (result) {
                    is NetworkResult.Success -> {
                        _pairingSuccessMessage.value = "Device successfully linked!"
                        _pairingState.value = null
                    }
                    is NetworkResult.Failure -> {
                        _pairingState.value = PairingState.Error("Failed to link device: ${result.userMessage}")
                    }
                }
            } catch (e: Exception) {
                val plainCode = qrJson.trim().replace("-", "").replace(" ", "").lowercase()
                if (plainCode.length == 8) {
                    fulfillReceiverCode(plainCode)
                } else {
                    _pairingState.value = PairingState.Error("Invalid QR code: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Fulfills a peer device's receiver session using an 8-character Crockford pairing code.
     */
    fun fulfillReceiverCode(code: String) {
        val cleanCode = code.trim().replace("-", "").replace(" ", "").lowercase()
        if (cleanCode.length != 8) {
            _pairingState.value = PairingState.Error("Pairing code must be 8 characters")
            return
        }
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            _pairingState.value = PairingState.AwaitingFulfillment
            when (val result = devicePairingManager.fulfillSenderWithCode(cleanCode)) {
                is NetworkResult.Success -> {
                    _pairingSuccessMessage.value = "Device successfully linked!"
                    _pairingState.value = null
                }
                is NetworkResult.Failure -> {
                    _pairingState.value = PairingState.Error("Failed to link device: ${result.userMessage}")
                }
            }
        }
    }

    /**
     * Stops the host pairing session and resets state.
     */
    fun stopHostPairing() {
        pairingJob?.cancel()
        pairingJob = null
        val current = _pairingState.value
        if (current is PairingState.Success) {
            current.vaultKey.close()
        }
        _pairingState.value = null
        _activePairingSession.value = null
        _pairingSuccessMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopHostPairing()
    }

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