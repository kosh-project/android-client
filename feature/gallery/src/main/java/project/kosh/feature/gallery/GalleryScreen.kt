package project.kosh.feature.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.delay
import project.kosh.core.auth.PairingState
import project.kosh.core.model.ActivePairingSession
import project.kosh.feature.auth.QrCameraScannerDialog
import project.kosh.feature.auth.QrCodeGenerator
import project.kosh.feature.gallery.ui.FloatingUploadBar
import project.kosh.feature.gallery.ui.FolderSelectionBottomSheet
import project.kosh.feature.gallery.ui.GalleryItemTile
import project.kosh.feature.gallery.ui.PermissionRationaleDialog

/**
 * Modern Jetpack Compose screen providing media vault viewing, empty state,
 * zero-knowledge encrypted thumbnails, folder sync onboarding, and batch CAS uploads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val galleryItems by viewModel.galleryItems.collectAsState()
    val activeUploadStages by viewModel.activeUploadStages.collectAsState()
    val batchUploadState by viewModel.batchUploadState.collectAsState()

    val mediaFolders by viewModel.mediaFolders.collectAsState()
    val isScanningFolders by viewModel.isScanningFolders.collectAsState()
    val showFolderSelectionSheet by viewModel.showFolderSelectionSheet.collectAsState()
    val showPermissionRationale by viewModel.showPermissionRationale.collectAsState()

    val uploadState by viewModel.uploadState.collectAsState()
    val activeUser by viewModel.activeUser.collectAsState()
    val isSessionActive by viewModel.isSessionActive.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    val activePairingSession by viewModel.activePairingSession.collectAsState()
    val pairingSuccessMessage by viewModel.pairingSuccessMessage.collectAsState()

    var isSessionExpired by remember(activePairingSession) { mutableStateOf(false) }

    LaunchedEffect(pairingState) {
        when (val p = pairingState) {
            is PairingState.Error -> {
                val err = p.message.lowercase()
                if (err.contains("expire") || err.contains("timed out") || err.contains("404") || err.contains("401")) {
                    isSessionExpired = true
                }
            }
            else -> {}
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

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showLinkDeviceBottomSheet by remember { mutableStateOf(false) }
    var showCameraScanner by remember { mutableStateOf(false) }
    var showManualCodeEntry by remember { mutableStateOf(false) }
    var manualPairingCode by remember { mutableStateOf("") }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showCameraScanner = true
        } else {
            Toast.makeText(
                context,
                "Camera permission is required to scan pairing QR code",
                Toast.LENGTH_SHORT
            ).show()
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
            Toast.makeText(
                context,
                "Media access is required to automatically discover and sync photo folders",
                Toast.LENGTH_SHORT
            ).show()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Encrypted Vault",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Vault",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSessionActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(6.dp)
                                ) {}
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (!activeUser.isNullOrBlank()) {
                                        "$activeUser • Active"
                                    } else if (isSessionActive) {
                                        "Hardware Vault Active"
                                    } else {
                                        "Session Locked"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.openFolderSelection() }) {
                        Icon(
                            imageVector = Icons.Default.FolderShared,
                            contentDescription = "Sync Folders",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        showLinkDeviceBottomSheet = true
                        viewModel.startHostPairing()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Link Device",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showLogoutDialog = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Log Out",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                icon = {
                    Icon(Icons.Default.Add, contentDescription = "Upload Media")
                },
                text = { Text("Upload") },
                shape = RoundedCornerShape(16.dp)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (galleryItems.isEmpty() && !batchUploadState.isUploading) {
                // Empty Vault State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(96.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Empty Vault",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = "Nothing uploaded yet",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Your private storage vault is currently empty. Photos and documents you upload will be chunked, encrypted with AES-256-GCM, and stored in your node's Content-Addressable Storage.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(Modifier.height(32.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Select Photos")
                        }

                        OutlinedButton(
                            onClick = { viewModel.openFolderSelection() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sync Folders")
                        }
                    }
                }
            } else {
                // Interactive Media Grid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(galleryItems, key = { it.asset.fileId }) { item ->
                        var thumbnailBitmap by remember(item.asset.fileId) { mutableStateOf<Bitmap?>(null) }

                        LaunchedEffect(item.asset.fileId) {
                            thumbnailBitmap = viewModel.loadThumbnail(item)
                        }

                        val uploadStage = activeUploadStages[item.asset.fileId]

                        GalleryItemTile(
                            item = item,
                            thumbnail = thumbnailBitmap,
                            uploadStage = uploadStage,
                            onClick = {
                                // Fullscreen viewer deferred to next iteration
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            // Bottom Floating Batch Upload Bar (Music Player Style)
            FloatingUploadBar(
                batchState = batchUploadState,
                onDismiss = { viewModel.dismissBatchBar() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp) // Pinned safely above Extended FAB
            )
        }
    }

    // Permission Rationale Onboarding Dialog
    if (showPermissionRationale) {
        PermissionRationaleDialog(
            onGrantPermission = {
                mediaPermissionLauncher.launch(mediaPermissions)
            },
            onDismiss = {
                viewModel.dismissPermissionRationale()
            }
        )
    }

    // MediaStore Folder Selection Modal Sheet
    if (showFolderSelectionSheet) {
        FolderSelectionBottomSheet(
            folders = mediaFolders,
            isLoading = isScanningFolders,
            onSaveSelection = { selectedBuckets ->
                viewModel.saveFolderSelection(selectedBuckets, context.contentResolver)
            },
            onDismiss = {
                viewModel.dismissFolderSelection()
            },
            thumbnailLoader = viewModel.thumbnailLoader
        )
    }

    // Logout Confirmation Dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = {
                Text("Log Out", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Logging out will lock your local Master Vault Key and clear the active session token from Android KeyStore. You will need to sign in again to access your encrypted files."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                        onLogout()
                    }
                ) {
                    Text("Log Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Link Device Modal Bottom Sheet (Flow B & Flow A: Bi-directional device pairing)
    if (showLinkDeviceBottomSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.stopHostPairing()
                showLinkDeviceBottomSheet = false
                showManualCodeEntry = false
                manualPairingCode = ""
            },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Link Device",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Link Another Device",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "Scan this QR code with your new device, or scan a code from that device to securely transfer your Master Vault Key.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))

                if (pairingSuccessMessage != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = pairingSuccessMessage ?: "Device successfully linked!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Your master vault key was securely transferred to the new device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            viewModel.stopHostPairing()
                            showLinkDeviceBottomSheet = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(26.dp)
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                } else {
                    val currentMailboxId = activePairingSession?.mailboxId
                        ?: (pairingState as? PairingState.AwaitingJoin)?.mailboxId
                        ?: (pairingState as? PairingState.Ready)?.mailboxId
                    val currentQrJson = activePairingSession?.qrPayloadJson
                        ?: (pairingState as? PairingState.AwaitingJoin)?.qrPayloadJson
                        ?: (pairingState as? PairingState.Ready)?.qrPayloadJson

                    val isError = pairingState is PairingState.Error && !isSessionExpired

                    if (isError) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = (pairingState as PairingState.Error).message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.startHostPairing() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Retry Pairing")
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                viewModel.stopHostPairing()
                                showLinkDeviceBottomSheet = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (currentMailboxId != null && currentQrJson != null) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .blur(blurRadius),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val formattedCode = if (currentMailboxId.length == 8) {
                                    "${currentMailboxId.take(4).uppercase()} - ${currentMailboxId.drop(4).uppercase()}"
                                } else {
                                    currentMailboxId.uppercase()
                                }

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isSessionExpired) {
                                            clipboardManager.setText(AnnotatedString(currentMailboxId))
                                            Toast.makeText(context, "Pairing code copied", Toast.LENGTH_SHORT).show()
                                        }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "PAIRING CODE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = formattedCode,
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            letterSpacing = 3.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Spacer(Modifier.height(16.dp))

                                val qrBitmap = remember(currentQrJson) {
                                    try {
                                        QrCodeGenerator.generate(currentQrJson, 512)
                                    } catch (_: Exception) {
                                        null
                                    }
                                }

                                if (qrBitmap != null) {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        shadowElevation = 2.dp,
                                        modifier = Modifier.size(210.dp)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(12.dp)
                                        ) {
                                            Image(
                                                bitmap = qrBitmap,
                                                contentDescription = "Pairing QR Code",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(14.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Scan this code with your new device...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (expiredOverlayAlpha > 0f) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f * expiredOverlayAlpha),
                                    tonalElevation = 6.dp,
                                    shadowElevation = 4.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp)
                                        .graphicsLayer { alpha = expiredOverlayAlpha }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Warning,
                                                    contentDescription = "Expired",
                                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            text = "Pairing Session Expired",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "The QR code has expired for security.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(Modifier.height(14.dp))
                                        Button(
                                            onClick = {
                                                viewModel.startHostPairing()
                                            },
                                            shape = RoundedCornerShape(24.dp),
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Generate New Code")
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.9f))
                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "Or scan a QR code from another device",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                        showCameraScanner = true
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text("Scan Camera QR", fontSize = 13.sp)
                            }

                            OutlinedButton(
                                onClick = { showManualCodeEntry = !showManualCodeEntry },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text(if (showManualCodeEntry) "Hide Code" else "Enter Code", fontSize = 13.sp)
                            }
                        }

                        if (showManualCodeEntry) {
                            Spacer(Modifier.height(14.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = manualPairingCode,
                                    onValueChange = { manualPairingCode = it },
                                    label = { Text("8-Character Code") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { viewModel.fulfillReceiverCode(manualPairingCode) },
                                    enabled = manualPairingCode.trim().replace("-", "").replace(" ", "").length == 8,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(54.dp)
                                ) {
                                    Text("Link")
                                }
                            }
                        }
                    } else {
                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Initializing secure pairing mailbox...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
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
