package project.kosh.feature.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import project.kosh.core.auth.AuthRepository
import project.kosh.core.auth.DevicePairingManager
import project.kosh.core.auth.PairingState
import project.kosh.core.database.crdt.MediaUploadPipeline
import project.kosh.core.database.crdt.UploadResult
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.database.repository.MediaRepository
import project.kosh.core.model.ActivePairingSession
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PairingQrPayload
import project.kosh.core.model.UploadStage
import project.kosh.core.model.UploadStatus
import project.kosh.core.network.AssetClient
import project.kosh.feature.gallery.loader.EncryptedThumbnailLoader
import project.kosh.feature.gallery.model.BatchUploadState
import project.kosh.feature.gallery.model.MediaFolder
import project.kosh.feature.gallery.sync.MediaFolderScanner
import project.kosh.feature.gallery.sync.SyncFolderPreferences
import java.util.UUID
import javax.inject.Inject

/**
 * Legacy UI State describing media upload progression for backwards compatibility.
 */
sealed interface UploadUiState {
    data object Idle : UploadUiState
    data class Uploading(val fileName: String) : UploadUiState
    data class Success(val fileName: String, val message: String = "Uploaded to vault") : UploadUiState
    data class Error(val message: String) : UploadUiState
}

/**
 * Internal descriptor for an in-flight upload task with allocated vault UUID.
 */
data class InFlightUploadItem(
    val fileId: String,
    val uri: Uri,
    val fileName: String,
    val contentResolver: ContentResolver
) {
    fun toMediaWithCryptoDetails(): MediaWithCryptoDetails =
        MediaWithCryptoDetails(
            asset = MediaAssetEntity(
                fileId = fileId,
                mediaType = "image/jpeg",
                fileSize = 0L,
                capturedAt = System.currentTimeMillis()
            ),
            cryptoMapping = MediaCryptoMappingEntity(
                fileId = fileId,
                plaintextSha256 = "",
                wrappedDek = "",
                uploadStatus = UploadStatus.UPLOADING
            )
        )
}

/**
 * ViewModel managing gallery media rendering, zero-knowledge streaming uploads,
 * folder sync preferences, device pairing, and session lifecycle.
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val assetClient: AssetClient,
    private val authRepository: AuthRepository,
    private val devicePairingManager: DevicePairingManager,
    private val mediaRepository: MediaRepository,
    private val mediaUploadPipeline: MediaUploadPipeline,
    private val mediaFolderScanner: MediaFolderScanner,
    private val syncFolderPreferences: SyncFolderPreferences,
    private val thumbnailLoader: EncryptedThumbnailLoader
) : ViewModel() {

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    // In-flight upload items keyed by assigned vault fileId
    private val _inFlightUploads = MutableStateFlow<Map<String, InFlightUploadItem>>(emptyMap())

    /**
     * Reactive stream of all active (non-deleted) vault media records combined with in-flight uploads.
     */
    val galleryItems: StateFlow<List<MediaWithCryptoDetails>> =
        combine(
            mediaRepository.observeAllMediaWithCrypto()
                .map { list -> list.filter { !it.asset.isDeleted } },
            _inFlightUploads
        ) { persistedList, inFlightMap ->
            val persistedFileIds = persistedList.map { it.asset.fileId }.toSet()
            val inFlightItems = inFlightMap.values
                .filter { it.fileId !in persistedFileIds }
                .map { it.toMediaWithCryptoDetails() }
            inFlightItems + persistedList
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Per-file upload progress stage map keyed by fileId and content URI string
    private val _activeUploadStages = MutableStateFlow<Map<String, UploadStage>>(emptyMap())
    val activeUploadStages: StateFlow<Map<String, UploadStage>> = _activeUploadStages.asStateFlow()

    // Aggregate batch upload progress state for floating bottom bar
    private val _batchUploadState = MutableStateFlow(BatchUploadState())
    val batchUploadState: StateFlow<BatchUploadState> = _batchUploadState.asStateFlow()

    // Folder sync state
    private val _mediaFolders = MutableStateFlow<List<MediaFolder>>(emptyList())
    val mediaFolders: StateFlow<List<MediaFolder>> = _mediaFolders.asStateFlow()

    private val _isScanningFolders = MutableStateFlow(false)
    val isScanningFolders: StateFlow<Boolean> = _isScanningFolders.asStateFlow()

    private val _showFolderSelectionSheet = MutableStateFlow(false)
    val showFolderSelectionSheet: StateFlow<Boolean> = _showFolderSelectionSheet.asStateFlow()

    private val _showPermissionRationale = MutableStateFlow(false)
    val showPermissionRationale: StateFlow<Boolean> = _showPermissionRationale.asStateFlow()

    // Legacy upload state for backwards compatibility
    private val _uploadState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uploadState: StateFlow<UploadUiState> = _uploadState.asStateFlow()

    private val _uploadStatus = MutableStateFlow("Idle")
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
    val activePairingSession: StateFlow<ActivePairingSession?> = _activePairingSession.asStateFlow()

    private val _pairingSuccessMessage = MutableStateFlow<String?>(null)
    val pairingSuccessMessage: StateFlow<String?> = _pairingSuccessMessage.asStateFlow()

    // Sequential Upload Queue
    private val uploadChannel = Channel<InFlightUploadItem>(Channel.UNLIMITED)
    private var uploadWorkerJob: Job? = null
    private var totalBatchCount = 0
    private var completedBatchCount = 0

    init {
        // Check first-time folder setup
        if (!syncFolderPreferences.hasCompletedFolderSetup()) {
            _showPermissionRationale.value = true
        }
    }

    /**
     * Enqueues a list of picked or scanned media URIs into the sequential upload queue.
     */
    fun enqueueUploads(contentResolver: ContentResolver, uris: List<Uri>) {
        if (uris.isEmpty()) return

        totalBatchCount += uris.size
        _batchUploadState.value = _batchUploadState.value.copy(
            totalItems = totalBatchCount,
            completedItems = completedBatchCount,
            isUploading = true
        )

        for (uri in uris) {
            val fileName = queryName(contentResolver, uri) ?: "kosh_${System.currentTimeMillis()}.jpg"
            val fileId = UUID.randomUUID().toString()
            val task = InFlightUploadItem(fileId, uri, fileName, contentResolver)
            _inFlightUploads.value = _inFlightUploads.value + (fileId to task)
            uploadChannel.trySend(task)
        }

        ensureUploadWorkerRunning()
    }

    /**
     * Uploads a single photo via the sequential queue (legacy interface).
     */
    fun uploadPhoto(contentResolver: ContentResolver, uri: Uri) {
        enqueueUploads(contentResolver, listOf(uri))
    }

    private fun ensureUploadWorkerRunning() {
        if (uploadWorkerJob?.isActive == true) return

        uploadWorkerJob = viewModelScope.launch(ioDispatcher) {
            for (task in uploadChannel) {
                processSingleUpload(task)
            }
            // All tasks finished
            _batchUploadState.value = _batchUploadState.value.copy(
                isUploading = false,
                currentStage = UploadStage.Completed
            )
            totalBatchCount = 0
            completedBatchCount = 0
        }
    }

    private suspend fun processSingleUpload(task: InFlightUploadItem) {
        val masterKey = authRepository.getVaultKey()

        if (masterKey == null) {
            val errorStage = UploadStage.Failed("Master Vault Key locked. Please sign in.")
            _activeUploadStages.value = _activeUploadStages.value + (task.fileId to errorStage) + (task.uri.toString() to errorStage)
            _uploadState.value = UploadUiState.Error("Master Vault Key locked")
            return
        }

        val currentIndex = completedBatchCount + 1
        _batchUploadState.value = _batchUploadState.value.copy(
            currentItemIndex = currentIndex,
            currentFileName = task.fileName,
            currentStage = UploadStage.Hashing(0f)
        )
        _uploadState.value = UploadUiState.Uploading(task.fileName)

        val initialStage = UploadStage.Hashing(0f)
        _activeUploadStages.value = _activeUploadStages.value + (task.fileId to initialStage) + (task.uri.toString() to initialStage)

        val result = mediaUploadPipeline.uploadFromUri(
            contentResolver = task.contentResolver,
            uri = task.uri,
            masterVaultKey = masterKey,
            originalFileName = task.fileName,
            customFileId = task.fileId,
            onProgress = { stage ->
                _activeUploadStages.value = _activeUploadStages.value + (task.fileId to stage) + (task.uri.toString() to stage)
                _batchUploadState.value = _batchUploadState.value.copy(
                    currentStage = stage
                )
            }
        )

        when (result) {
            is UploadResult.Success -> {
                completedBatchCount++
                val resolvedFileId = result.asset.fileId
                _batchUploadState.value = _batchUploadState.value.copy(
                    completedItems = completedBatchCount,
                    currentStage = UploadStage.Completed
                )
                _activeUploadStages.value = _activeUploadStages.value + (task.fileId to UploadStage.Completed) + (resolvedFileId to UploadStage.Completed) + (task.uri.toString() to UploadStage.Completed)
                _uploadState.value = UploadUiState.Success(task.fileName, "Stored in vault")

                // Keep completion indicator visible briefly for smooth animation
                delay(600L)
                _activeUploadStages.value = _activeUploadStages.value - task.fileId - resolvedFileId - task.uri.toString()
                _inFlightUploads.value = _inFlightUploads.value - task.fileId
            }
            is UploadResult.Error -> {
                completedBatchCount++
                val errorStage = UploadStage.Failed(result.message)
                _activeUploadStages.value = _activeUploadStages.value + (task.fileId to errorStage) + (task.uri.toString() to errorStage)
                _uploadState.value = UploadUiState.Error(result.message)
                delay(1200L)
                _activeUploadStages.value = _activeUploadStages.value - task.fileId - task.uri.toString()
                _inFlightUploads.value = _inFlightUploads.value - task.fileId
            }
        }
    }

    /**
     * Resolves a thumbnail bitmap for the given [item] through the 2-tier cache and CAS decryptor.
     */
    suspend fun loadThumbnail(item: MediaWithCryptoDetails): Bitmap? {
        val inFlight = _inFlightUploads.value[item.asset.fileId]
        if (inFlight != null) {
            val localBitmap = thumbnailLoader.loadLocalThumbnail(inFlight.contentResolver, inFlight.uri, item.asset.fileId)
            if (localBitmap != null) return localBitmap
        }
        val masterKey = authRepository.getVaultKey()
        return thumbnailLoader.loadThumbnail(item, masterKey)
    }

    /**
     * Queries all device media folders via MediaStore.
     */
    fun loadFolders() {
        viewModelScope.launch {
            _isScanningFolders.value = true
            _mediaFolders.value = mediaFolderScanner.queryMediaFolders()
            _isScanningFolders.value = false
        }
    }

    /**
     * Saves user's selected sync bucket IDs, marks onboarding complete, and enqueues unsynced items.
     */
    fun saveFolderSelection(selectedBucketIds: Set<String>, contentResolver: ContentResolver) {
        syncFolderPreferences.setEnabledBucketIds(selectedBucketIds)
        syncFolderPreferences.setFolderSetupCompleted(true)
        _showFolderSelectionSheet.value = false
        _showPermissionRationale.value = false

        viewModelScope.launch(ioDispatcher) {
            val urisToSync = mutableListOf<Uri>()
            for (bucketId in selectedBucketIds) {
                val items = mediaFolderScanner.queryMediaInFolder(bucketId)
                urisToSync.addAll(items.map { it.uri })
            }
            if (urisToSync.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    enqueueUploads(contentResolver, urisToSync)
                }
            }
        }
    }

    fun openFolderSelection() {
        loadFolders()
        _showFolderSelectionSheet.value = true
    }

    fun dismissFolderSelection() {
        _showFolderSelectionSheet.value = false
    }

    fun onPermissionGranted(contentResolver: ContentResolver) {
        _showPermissionRationale.value = false
        openFolderSelection()
    }

    fun dismissPermissionRationale() {
        _showPermissionRationale.value = false
        syncFolderPreferences.setFolderSetupCompleted(true)
    }

    fun dismissBatchBar() {
        _batchUploadState.value = _batchUploadState.value.copy(isUploading = false)
    }

    fun dismissUploadState() {
        _uploadState.value = UploadUiState.Idle
        _uploadStatus.value = "Idle"
    }

    // --- Device Pairing Flow & Logout ---

    fun startHostPairing() {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            _pairingState.value = null
            _activePairingSession.value = null
            _pairingSuccessMessage.value = null
            try {
                devicePairingManager.startHostFlow().collect { pState ->
                    if (pState is PairingState.AwaitingJoin || pState is PairingState.Ready) {
                        val mbId = (pState as? PairingState.AwaitingJoin)?.mailboxId
                            ?: (pState as? PairingState.Ready)?.mailboxId ?: ""
                        val qr = (pState as? PairingState.AwaitingJoin)?.qrPayloadJson
                            ?: (pState as? PairingState.Ready)?.qrPayloadJson ?: ""
                        val exp = (pState as? PairingState.AwaitingJoin)?.expiresInSeconds
                            ?: (pState as? PairingState.Ready)?.expiresInSeconds ?: 120L
                        _activePairingSession.value = ActivePairingSession(mbId, qr, exp)
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
        thumbnailLoader.clearMemoryCache()
    }

    fun isSessionActive(): Boolean = authRepository.isLoggedIn()

    fun logout() {
        authRepository.logout()
        _isSessionActive.value = false
        _activeUser.value = null
        thumbnailLoader.clearMemoryCache()
        thumbnailLoader.clearDiskCache()
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? {
        try {
            resolver.query(uri, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
