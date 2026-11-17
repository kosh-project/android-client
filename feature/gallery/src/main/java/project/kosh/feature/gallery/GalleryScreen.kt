package project.kosh.feature.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import project.kosh.core.auth.PairingState
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.model.UploadStage
import project.kosh.core.network.PingResult
import project.kosh.feature.auth.QrCameraScannerDialog
import project.kosh.feature.gallery.ui.FolderSelectionBottomSheet
import project.kosh.feature.gallery.ui.GalleryScreenContent
import project.kosh.feature.gallery.ui.GalleryThumbnailProvider
import project.kosh.feature.gallery.ui.LinkDeviceBottomSheet
import project.kosh.feature.gallery.ui.LogoutConfirmationDialog
import project.kosh.feature.gallery.ui.PermissionRationaleDialog

/**
 * Stateful screen orchestrating ViewModel state flows, activity result contracts,
 * hardware camera/media permissions, and dialog presentation for the gallery vault.
 *
 * Delegates pure UI rendering to the stateless [GalleryScreenContent].
 *
 * @param viewModel State coordinator injected via Hilt.
 * @param onLogout Invoked after active session credentials have been cleared.
 * @param onOpenServerConfig Invoked to navigate to remote server setup screen.
 */
@Composable
fun GalleryScreen(
    @Suppress("DEPRECATION")
    viewModel: GalleryViewModel = hiltViewModel(),
    onLogout: () -> Unit = {},
    onOpenServerConfig: () -> Unit = {}
) {
    val context = LocalContext.current

    val groupedGalleryItems by viewModel.groupedGalleryItems.collectAsState()
    val isBatchUploading by viewModel.isBatchUploading.collectAsState()
    val batchUploadState by viewModel.batchUploadState.collectAsState()

    val mediaFolders by viewModel.mediaFolders.collectAsState()
    val isScanningFolders by viewModel.isScanningFolders.collectAsState()
    val showFolderSelectionSheet by viewModel.showFolderSelectionSheet.collectAsState()
    val showPermissionRationale by viewModel.showPermissionRationale.collectAsState()

    val activeUser by viewModel.activeUser.collectAsState()
    val isSessionActive by viewModel.isSessionActive.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    val activePairingSession by viewModel.activePairingSession.collectAsState()
    val pairingSuccessMessage by viewModel.pairingSuccessMessage.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()

    var isSessionExpired by remember(activePairingSession) { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showLinkDeviceBottomSheet by remember { mutableStateOf(false) }
    var showCameraScanner by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.checkAndRestoreSnapshot()
    }

    LaunchedEffect(pairingState) {
        if (pairingState is PairingState.Error) {
            val err = (pairingState as PairingState.Error).message.lowercase()
            if (err.contains("expire") || err.contains("timed out") || err.contains("404") || err.contains("401")) {
                isSessionExpired = true
            }
        }
    }

    LaunchedEffect(activePairingSession) {
        if (activePairingSession == null) {
            isSessionExpired = false
            return@LaunchedEffect
        }
        val session = activePairingSession!!
        while (true) {
            val elapsedSec = (System.currentTimeMillis() - session.createdAtEpochMs) / 1000
            if (elapsedSec >= session.expiresInSeconds) {
                isSessionExpired = true
                break
            }
            delay(1000L)
        }
    }

    val blurRadius by animateDpAsState(
        targetValue = if (isSessionExpired) 16.dp else 0.dp,
        animationSpec = tween(durationMillis = 600),
        label = "galleryMailboxBlur"
    )

    val expiredOverlayAlpha by animateFloatAsState(
        targetValue = if (isSessionExpired) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "galleryExpiredOverlayAlpha"
    )

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showCameraScanner = true
        } else {
            Toast.makeText(context, "Camera permission is required to scan pairing QR code", Toast.LENGTH_SHORT).show()
        }
    }

    val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val anyGranted = permissions.values.any { it }
        if (anyGranted) {
            viewModel.onPermissionGranted(context.contentResolver)
        } else {
            Toast.makeText(context, "Media access is required to automatically discover and sync photo folders", Toast.LENGTH_SHORT).show()
            viewModel.dismissPermissionRationale()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.enqueueUploads(context.contentResolver, uris)
        }
    }

    val thumbnailProvider = remember(viewModel) {
        object : GalleryThumbnailProvider {
            override fun getFromMemoryCache(item: MediaWithCryptoDetails): Bitmap? =
                viewModel.getFromMemoryCache(item)

            override fun hasDiskCache(fileId: String): Boolean =
                viewModel.hasDiskCache(fileId)

            override fun isInFlight(fileId: String): Boolean =
                viewModel.isInFlight(fileId)

            override suspend fun loadThumbnail(item: MediaWithCryptoDetails): Bitmap? =
                viewModel.loadThumbnail(item)

            override fun getUploadStage(fileId: String): Flow<UploadStage?> =
                viewModel.getUploadStage(fileId)
        }
    }

    GalleryScreenContent(
        groupedGalleryItems = groupedGalleryItems,
        isBatchUploading = isBatchUploading,
        batchUploadState = batchUploadState,
        isSyncing = isSyncing,
        activeUser = activeUser,
        isSessionActive = isSessionActive,
        isPinging = isPinging,
        thumbnailProvider = thumbnailProvider,
        onLaunchPhotoPicker = {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        },
        onDismissBatchBar = { viewModel.dismissBatchBar() },
        onRefreshSync = { viewModel.triggerManualSync() },
        onOpenFolderSelection = { viewModel.openFolderSelection() },
        onStartPairing = {
            showLinkDeviceBottomSheet = true
            viewModel.startHostPairing()
        },
        onOpenServerConfig = onOpenServerConfig,
        onPingServer = {
            viewModel.pingServer { result ->
                val msg = when (result) {
                    is PingResult.Success -> "Server online (${result.latencyMs}ms)"
                    is PingResult.Failure -> "Server unreachable (${result.error})"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        },
        onLogoutRequest = { showLogoutDialog = true }
    )

    if (showPermissionRationale) {
        PermissionRationaleDialog(
            onGrantPermission = { mediaPermissionLauncher.launch(mediaPermissions) },
            onDismiss = { viewModel.dismissPermissionRationale() }
        )
    }

    if (showFolderSelectionSheet) {
        FolderSelectionBottomSheet(
            folders = mediaFolders,
            isLoading = isScanningFolders,
            onSaveSelection = { selectedBuckets ->
                viewModel.saveFolderSelection(selectedBuckets, context.contentResolver)
            },
            onDismiss = { viewModel.dismissFolderSelection() },
            thumbnailLoader = viewModel.thumbnailLoader
        )
    }

    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            onConfirm = {
                showLogoutDialog = false
                viewModel.logout()
                onLogout()
            },
            onDismiss = { showLogoutDialog = false }
        )
    }

    if (showLinkDeviceBottomSheet) {
        LinkDeviceBottomSheet(
            activePairingSession = activePairingSession,
            pairingState = pairingState,
            pairingSuccessMessage = pairingSuccessMessage,
            isSessionExpired = isSessionExpired,
            blurRadius = blurRadius,
            expiredOverlayAlpha = expiredOverlayAlpha,
            onRetryPairing = { viewModel.startHostPairing() },
            onScanCameraQr = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    showCameraScanner = true
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onFulfillReceiverCode = { code -> viewModel.fulfillReceiverCode(code) },
            onDismiss = {
                viewModel.stopHostPairing()
                showLinkDeviceBottomSheet = false
            }
        )
    }

    if (showCameraScanner) {
        QrCameraScannerDialog(
            onDismiss = { showCameraScanner = false },
            onScanned = { _, scannedJson ->
                showCameraScanner = false
                if (scannedJson != null) {
                    viewModel.fulfillReceiverQr(scannedJson)
                }
            }
        )
    }
}
