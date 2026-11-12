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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import project.kosh.core.auth.AuthRepository
import project.kosh.core.auth.DevicePairingManager
import project.kosh.core.auth.PairingState
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.database.crdt.CrdtSyncEngine
import project.kosh.core.database.crdt.MediaUploadPipeline
import project.kosh.core.database.crdt.SnapshotSyncManager
import project.kosh.core.database.crdt.UploadResult
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.database.repository.MediaRepository
import project.kosh.core.model.ActivePairingSession
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PairingQrPayload
import project.kosh.core.model.SyncNamespace
import project.kosh.core.model.UploadStage
import project.kosh.core.model.UploadStatus
import project.kosh.feature.gallery.sync.SnapshotWorkManagerScheduler
import project.kosh.core.network.AssetClient
import project.kosh.core.network.PingResult
import project.kosh.core.network.ServerConfigurationStore
import project.kosh.core.network.ServerHealthPinger
import project.kosh.feature.gallery.loader.EncryptedThumbnailLoader
import project.kosh.feature.gallery.model.BatchUploadState
import project.kosh.feature.gallery.model.DiscoveredMediaItem
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
    val contentResolver: ContentResolver,
    val dateModified: Long = System.currentTimeMillis(),
    val mimeType: String = "image/jpeg",
    val fileSize: Long = 0L
) {
    fun toMediaWithCryptoDetails(): MediaWithCryptoDetails =
        MediaWithCryptoDetails(
            asset = MediaAssetEntity(
                fileId = fileId,
                mediaType = mimeType,
                fileSize = fileSize,
                capturedAt = dateModified
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
    val thumbnailLoader: EncryptedThumbnailLoader,
    private val crdtSyncEngine: CrdtSyncEngine,
    private val snapshotSyncManager: SnapshotSyncManager,
    private val mediaAssetDao: MediaAssetDao,
    private val snapshotScheduler: SnapshotWorkManagerScheduler,
    private val serverHealthPinger: ServerHealthPinger? = null,
    private val serverConfigStore: ServerConfigurationStore? = null
) : ViewModel() {

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _pingResult = MutableStateFlow<PingResult?>(null)
    val pingResult: StateFlow<PingResult?> = _pingResult.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    fun pingServer(onResult: ((PingResult) -> Unit)? = null) {
        val pinger = serverHealthPinger
        val store = serverConfigStore
        if (pinger == null || store == null) {
            val failure = PingResult.Failure("Pinger not initialized", false)
            _pingResult.value = failure
            onResult?.invoke(failure)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            _isPinging.value = true
            val config = store.getServerConfig()
            val result = pinger.ping(config.host, config.port, config.useTls)
            _pingResult.value = result
            _isPinging.value = false
            withContext(Dispatchers.Main) {
                onResult?.invoke(result)
            }
        }
    }

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
            (inFlightItems + persistedList).sortedWith(
                compareByDescending<MediaWithCryptoDetails> { it.asset.capturedAt }
                    .thenByDescending { it.asset.fileId }
            )
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

    // Manual pull-to-refresh sync state
    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()
    val isSyncing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

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
        if (authRepository.isLoggedIn()) {
            snapshotScheduler.schedulePeriodicSnapshot()
        }
    }

    /**
     * Enqueues a list of picked or scanned media URIs into the sequential upload queue.
     */
    fun enqueueUploads(contentResolver: ContentResolver, uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch(ioDispatcher) {
            val inFlightUris = _inFlightUploads.value.values.map { it.uri }.toSet()
            val uniqueUris = uris.distinct().filter { it !in inFlightUris }
            if (uniqueUris.isEmpty()) return@launch

            val newTasks = mutableListOf<InFlightUploadItem>()
            val newTasksMap = mutableMapOf<String, InFlightUploadItem>()

            for (uri in uniqueUris) {
                val (fileName, fileSize) = queryNameAndSize(contentResolver, uri)
                val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
                val fileId = UUID.randomUUID().toString()
                val task = InFlightUploadItem(
                    fileId = fileId,
                    uri = uri,
                    fileName = fileName ?: "kosh_${System.currentTimeMillis()}.jpg",
                    contentResolver = contentResolver,
                    mimeType = mimeType,
                    fileSize = fileSize
                )
                newTasks.add(task)
                newTasksMap[fileId] = task
            }

            totalBatchCount += uniqueUris.size
            _batchUploadState.value = _batchUploadState.value.copy(
                totalItems = totalBatchCount,
                completedItems = completedBatchCount,
                isUploading = true
            )

            _inFlightUploads.value = _inFlightUploads.value + newTasksMap

            for (task in newTasks) {
                uploadChannel.trySend(task)
            }

            ensureUploadWorkerRunning()
        }
    }

    /**
     * Uploads a single photo via the sequential queue (legacy interface).
     */
    fun uploadPhoto(contentResolver: ContentResolver, uri: Uri) {
        enqueueUploads(contentResolver, listOf(uri))
    }

    private var ledgerTickerJob: Job? = null

    private fun startLedgerTicker(masterKey: MasterVaultKey) {
        if (ledgerTickerJob?.isActive == true) return
        ledgerTickerJob = viewModelScope.launch(ioDispatcher) {
            while (isActive) {
                delay(90_000L)
                val currentKey = authRepository.getVaultKey() ?: masterKey
                if (currentKey.isClosed || (!authRepository.isLoggedIn() && authRepository.getVaultKey() == null)) {
                    break
                }
                try {
                    if (crdtSyncEngine.getPendingQueueCount("gallery") > 0) {
                        crdtSyncEngine.sync(currentKey, SyncNamespace.GALLERY)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Safe isolation: CAS upload is never disrupted by CRDT sync issues
                }
            }
        }
    }

    private fun stopLedgerTicker() {
        ledgerTickerJob?.cancel()
        ledgerTickerJob = null
    }

    private fun ensureUploadWorkerRunning() {
        val masterKey = authRepository.getVaultKey()
        if (masterKey != null && !masterKey.isClosed) {
            startLedgerTicker(masterKey)
        }

        if (uploadWorkerJob?.isActive == true) return

        uploadWorkerJob = viewModelScope.launch(ioDispatcher) {
            for (task in uploadChannel) {
                processSingleUpload(task)
                if (completedBatchCount >= totalBatchCount) {
                    stopLedgerTicker()
                    val key = authRepository.getVaultKey() ?: masterKey
                    if (key != null && !key.isClosed) {
                        try {
                            crdtSyncEngine.sync(key, SyncNamespace.GALLERY)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            // Safe isolation
                        }
                    }

                    _batchUploadState.value = _batchUploadState.value.copy(
                        isUploading = false,
                        totalItems = 0,
                        completedItems = 0,
                        currentStage = UploadStage.Completed
                    )
                    totalBatchCount = 0
                    completedBatchCount = 0
                }
            }
        }
    }

    private suspend fun processSingleUpload(task: InFlightUploadItem) {
        val masterKey = authRepository.getVaultKey()

        if (masterKey == null) {
            completedBatchCount++
            val errorStage = UploadStage.Failed("Master Vault Key locked. Please sign in.")
            _activeUploadStages.value = _activeUploadStages.value + (task.fileId to errorStage) + (task.uri.toString() to errorStage)
            _uploadState.value = UploadUiState.Error("Master Vault Key locked")
            delay(1200L)
            _activeUploadStages.value = _activeUploadStages.value - task.fileId - task.uri.toString()
            _inFlightUploads.value = _inFlightUploads.value - task.fileId
            return
        }

        if (ledgerTickerJob?.isActive != true) {
            startLedgerTicker(masterKey)
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
            mediaType = task.mimeType,
            originalFileName = task.fileName,
            customFileId = task.fileId,
            customCapturedAt = task.dateModified,
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
            val localBitmap = thumbnailLoader.loadLocalThumbnail(
                contentResolver = inFlight.contentResolver,
                uri = inFlight.uri,
                fileId = item.asset.fileId,
                mimeType = inFlight.mimeType
            )
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
            val allDiscoveredItems = mutableListOf<DiscoveredMediaItem>()
            for (bucketId in selectedBucketIds) {
                val items = mediaFolderScanner.queryMediaInFolder(bucketId)
                allDiscoveredItems.addAll(items)
            }
            if (allDiscoveredItems.isEmpty()) return@launch

            val inFlightUris = _inFlightUploads.value.values.map { it.uri }.toSet()
            val uniqueItems = allDiscoveredItems.distinctBy { it.uri }.filter { it.uri !in inFlightUris }
            if (uniqueItems.isEmpty()) return@launch

            val newTasks = mutableListOf<InFlightUploadItem>()
            val newTasksMap = mutableMapOf<String, InFlightUploadItem>()

            for (item in uniqueItems) {
                val fileId = UUID.randomUUID().toString()
                val task = InFlightUploadItem(
                    fileId = fileId,
                    uri = item.uri,
                    fileName = item.displayName,
                    contentResolver = contentResolver,
                    dateModified = item.dateModified,
                    mimeType = item.mimeType,
                    fileSize = item.size
                )
                newTasks.add(task)
                newTasksMap[fileId] = task
            }

            totalBatchCount += newTasks.size
            _batchUploadState.value = _batchUploadState.value.copy(
                totalItems = totalBatchCount,
                completedItems = completedBatchCount,
                isUploading = true
            )

            _inFlightUploads.value = _inFlightUploads.value + newTasksMap

            for (task in newTasks) {
                uploadChannel.trySend(task)
            }

            ensureUploadWorkerRunning()
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
                var effectiveResult = if (rxPubKey.isNotBlank()) {
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

                // Fallback: if not found on current server and an alternate host is present in the QR code, retry with QR server
                val payloadHost = payload.host
                val store = serverConfigStore
                if (effectiveResult is NetworkResult.Failure && !payloadHost.isNullOrBlank() && store != null) {
                    val current = store.getServerConfig()
                    if (payloadHost != current.host || (payload.port != null && payload.port != current.port)) {
                        val fingerprintBytes = payload.fingerprint?.let {
                            ServerConfigurationStore.parseFingerprint(it)
                        } ?: current.pinnedCertFingerprintSha256
                        store.updateServer(
                            host = payloadHost,
                            port = payload.port ?: 23342,
                            useTls = payload.fingerprint != null || store.isTlsEnabled(),
                            pinnedCertFingerprintSha256 = fingerprintBytes
                        )
                        effectiveResult = if (rxPubKey.isNotBlank()) {
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
                        if (effectiveResult is NetworkResult.Failure) {
                            store.updateServer(
                                host = current.host,
                                port = current.port,
                                useTls = current.useTls,
                                pinnedCertFingerprintSha256 = current.pinnedCertFingerprintSha256
                            )
                        }
                    }
                }

                when (effectiveResult) {
                    is NetworkResult.Success -> {
                        _pairingSuccessMessage.value = "Device successfully linked!"
                        _pairingState.value = null
                    }
                    is NetworkResult.Failure -> {
                        _pairingState.value = PairingState.Error("Failed to link device: ${effectiveResult.userMessage}")
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
        snapshotScheduler.cancelPeriodicSnapshot()
        manualSyncJob?.cancel()
        manualSyncJob = null
        stopLedgerTicker()
        _isManualRefreshing.value = false
        authRepository.logout()
        _isSessionActive.value = false
        _activeUser.value = null
        thumbnailLoader.clearMemoryCache()
        thumbnailLoader.clearDiskCache()
    }

    /**
     * Checks if the local database has 0 assets; if so, pulls and materializes the latest snapshot,
     * and performs a follow-up delta sync to bring the client fully up to date.
     * If the local database already has assets, triggers delta synchronization directly.
     */
    fun checkAndRestoreSnapshot(): Job = viewModelScope.launch(ioDispatcher) {
        val masterKey = authRepository.getVaultKey() ?: return@launch
        if (masterKey.isClosed) return@launch
        try {
            if (mediaAssetDao.getCount() == 0) {
                try {
                    snapshotSyncManager.restoreFromLatestSnapshot(masterKey, SyncNamespace.GALLERY)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Continue to delta sync if snapshot fetch fails or no snapshot exists
                }
            }
            crdtSyncEngine.sync(masterKey, SyncNamespace.GALLERY)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Safe isolation
        }
    }

    private var manualSyncJob: Job? = null

    /**
     * Triggers a manual bidirectional Delta-CRDT sync for the gallery namespace.
     */
    fun triggerManualSync(): Job {
        val masterKey = authRepository.getVaultKey()
        if (masterKey == null || masterKey.isClosed || !authRepository.isLoggedIn()) {
            return Job().apply { complete() }
        }
        if (!_isManualRefreshing.compareAndSet(expect = false, update = true)) {
            return manualSyncJob ?: Job().apply { complete() }
        }
        val job = viewModelScope.launch(ioDispatcher) {
            try {
                withTimeoutOrNull(2000L) {
                    crdtSyncEngine.sync(masterKey, SyncNamespace.GALLERY)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Safe isolation: network or ledger errors never crash the UI
            } finally {
                _isManualRefreshing.value = false
            }
        }
        manualSyncJob = job
        return job
    }

    /**
     * Creates and registers a new encrypted point-in-time state snapshot with the server.
     */
    fun createSnapshot(): Job = viewModelScope.launch(ioDispatcher) {
        val masterKey = authRepository.getVaultKey() ?: return@launch
        if (masterKey.isClosed) return@launch
        try {
            snapshotSyncManager.createAndUploadSnapshot(masterKey, SyncNamespace.GALLERY)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Safe isolation
        }
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String?, Long> {
        try {
            resolver.query(uri, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    val name = if (nameIndex != -1) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L
                    return Pair(name, size)
                }
            }
        } catch (_: Exception) {}
        return Pair(null, 0L)
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? {
        return queryNameAndSize(resolver, uri).first
    }
}
