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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import project.kosh.core.auth.PairingState
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.model.ActivePairingSession
import project.kosh.core.network.PingResult
import project.kosh.feature.auth.QrCameraScannerDialog
import project.kosh.feature.auth.QrCodeGenerator
import project.kosh.feature.gallery.ui.FloatingUploadBar
import project.kosh.feature.gallery.ui.FolderSelectionBottomSheet
import project.kosh.feature.gallery.ui.GalleryItemTile
import project.kosh.feature.gallery.ui.PermissionRationaleDialog
import project.kosh.feature.gallery.ui.icons.FolderIcon
import project.kosh.feature.gallery.ui.icons.LinkIcon
import project.kosh.feature.gallery.ui.icons.LogoutIcon
import project.kosh.feature.gallery.ui.icons.MoreVertIcon
import project.kosh.feature.gallery.ui.icons.NetworkPingIcon
import project.kosh.feature.gallery.ui.icons.SettingsIcon
import project.kosh.feature.gallery.ui.icons.SyncIcon

private data class GalleryDateGroup(
    val key: String,
    val title: String,
    val items: List<MediaWithCryptoDetails>
)

/**
 * Modern Jetpack Compose screen providing media vault viewing, empty state,
 * zero-knowledge encrypted thumbnails, folder sync onboarding, and batch CAS uploads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    onLogout: () -> Unit = {},
    onOpenServerConfig: () -> Unit = {}
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
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()

    var isSessionExpired by remember(activePairingSession) { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.checkAndRestoreSnapshot()
    }

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

    var columnCount by remember { mutableIntStateOf(4) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showLinkDeviceBottomSheet by remember { mutableStateOf(false) }
    var showCameraScanner by remember { mutableStateOf(false) }
    var showManualCodeEntry by remember { mutableStateOf(false) }
    var manualPairingCode by remember { mutableStateOf("") }

    val gridState = rememberLazyGridState()
    var isFabExpanded by remember { mutableStateOf(true) }
    var prevIndex by remember { mutableIntStateOf(0) }
    var prevScrollOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (index == 0 && offset == 0) {
                    isFabExpanded = true
                    prevIndex = 0
                    prevScrollOffset = 0
                } else if (index > prevIndex || (index == prevIndex && offset > prevScrollOffset + 15)) {
                    isFabExpanded = false
                    prevIndex = index
                    prevScrollOffset = offset
                } else if (index < prevIndex || (index == prevIndex && offset < prevScrollOffset - 15)) {
                    isFabExpanded = true
                    prevIndex = index
                    prevScrollOffset = offset
                }
            }
    }

    val groupedGalleryItems = remember(galleryItems) {
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val yesterday = today.minusDays(1)
        val sameYearFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        val diffYearFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

        val map = linkedMapOf<LocalDate, MutableList<MediaWithCryptoDetails>>()
        for (item in galleryItems) {
            val ts = if (item.asset.capturedAt > 0) item.asset.capturedAt else item.asset.createdAt
            val date = Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate()
            map.getOrPut(date) { mutableListOf() }.add(item)
        }

        map.map { (date, items) ->
            val title = when {
                date.isEqual(today) -> "Today"
                date.isEqual(yesterday) -> "Yesterday"
                date.year == today.year -> sameYearFormatter.format(date)
                else -> diffYearFormatter.format(date)
            }
            GalleryDateGroup(key = date.toString(), title = title, items = items)
        }
    }

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
                    var showTopMenu by remember { mutableStateOf(false) }

                    IconButton(onClick = { showTopMenu = true }) {
                        Icon(
                            imageVector = MoreVertIcon,
                            contentDescription = "More Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = showTopMenu,
                        onDismissRequest = { showTopMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Media folders") },
                            leadingIcon = {
                                Icon(
                                    imageVector = FolderIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showTopMenu = false
                                viewModel.openFolderSelection()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Link device") },
                            leadingIcon = {
                                Icon(
                                    imageVector = LinkIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showTopMenu = false
                                showLinkDeviceBottomSheet = true
                                viewModel.startHostPairing()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Sync vault") },
                            leadingIcon = {
                                Icon(
                                    imageVector = SyncIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showTopMenu = false
                                viewModel.triggerManualSync()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Server settings") },
                            leadingIcon = {
                                Icon(
                                    imageVector = SettingsIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showTopMenu = false
                                onOpenServerConfig()
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                if (isPinging) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Pinging server...")
                                        Spacer(Modifier.width(8.dp))
                                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                    }
                                } else {
                                    Text("Ping server")
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = NetworkPingIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            enabled = !isPinging,
                            onClick = {
                                showTopMenu = false
                                viewModel.pingServer { result ->
                                    val msg = when (result) {
                                        is PingResult.Success -> "Server online (${result.latencyMs}ms)"
                                        is PingResult.Failure -> "Server unreachable (${result.error})"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Log out",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = LogoutIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showTopMenu = false
                                showLogoutDialog = true
                            }
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
                expanded = isFabExpanded,
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
        val coroutineScope = rememberCoroutineScope()
        var isManualRefreshingVisual by remember { mutableStateOf(false) }
        var isDismissingAfterRefresh by remember { mutableStateOf(false) }

        val pullToRefreshAnim = remember { Animatable(0f, Float.VectorConverter) }
        val pullToRefreshState = remember(pullToRefreshAnim) {
            object : PullToRefreshState {
                override val distanceFraction: Float
                    get() = pullToRefreshAnim.value

                override val isAnimating: Boolean
                    get() = pullToRefreshAnim.isRunning

                override suspend fun animateToThreshold() {
                    pullToRefreshAnim.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                }

                override suspend fun animateToHidden() {
                    pullToRefreshAnim.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                }

                override suspend fun snapTo(targetValue: Float) {
                    pullToRefreshAnim.snapTo(targetValue)
                }
            }
        }

        val isRefreshing = isManualRefreshingVisual || isSyncing

        val overstretchProgress = pullToRefreshState.distanceFraction.coerceIn(0f, 1.5f)
        val overstretchY = (96.dp * overstretchProgress)

        val animatedScaleRelaxation by animateFloatAsState(
            targetValue = if (isRefreshing || isDismissingAfterRefresh) 0f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "galleryPullScaleRelaxation"
        )
        val overstretchScaleY = 1f + (overstretchProgress * 0.06f * animatedScaleRelaxation)

        val indicatorAlpha by animateFloatAsState(
            targetValue = when {
                isRefreshing -> 1f
                isDismissingAfterRefresh -> 0f
                pullToRefreshState.isAnimating -> 0f
                overstretchProgress > 0.05f -> (overstretchProgress * 2.5f).coerceIn(0f, 1f)
                else -> 0f
            },
            animationSpec = tween(durationMillis = 250),
            label = "galleryIndicatorAlpha"
        )

        val indicatorScale by animateFloatAsState(
            targetValue = when {
                isRefreshing -> 1f
                isDismissingAfterRefresh -> 0.8f
                pullToRefreshState.isAnimating -> 0.8f
                overstretchProgress > 0.05f -> (0.6f + overstretchProgress * 0.4f).coerceIn(0.6f, 1f)
                else -> 0.6f
            },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "galleryIndicatorScale"
        )

        val executeRefresh: () -> Unit = {
            coroutineScope.launch {
                try {
                    isDismissingAfterRefresh = false
                    isManualRefreshingVisual = true
                    val syncJob = viewModel.triggerManualSync()
                    val startTime = System.currentTimeMillis()
                    syncJob.join()
                    val elapsed = System.currentTimeMillis() - startTime
                    val minDisplayMs = 2000L
                    if (elapsed < minDisplayMs) {
                        delay(minDisplayMs - elapsed)
                    }
                    isDismissingAfterRefresh = true
                    isManualRefreshingVisual = false
                    pullToRefreshState.animateToHidden()
                } finally {
                    isDismissingAfterRefresh = false
                    isManualRefreshingVisual = false
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = executeRefresh,
            state = pullToRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            indicator = {}
        ) {
            // Overstretched Header Space containing M3 Loading Indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(overstretchY)
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = indicatorAlpha
                        scaleX = indicatorScale
                        scaleY = indicatorScale
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isRefreshing || isDismissingAfterRefresh) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        strokeCap = StrokeCap.Round
                    )
                } else if (overstretchProgress > 0.05f) {
                    val pullProgress = (overstretchProgress / 1.0f).coerceIn(0f, 1f)
                    CircularProgressIndicator(
                        progress = { pullProgress },
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        strokeCap = StrokeCap.Round
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = overstretchY.toPx()
                        scaleY = overstretchScaleY
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
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
                    columns = GridCells.Fixed(columnCount),
                    state = gridState,
                    contentPadding = PaddingValues(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Scroll) {
                                        val scrollDelta = event.changes.fold(0f) { acc, c -> acc + c.scrollDelta.y }
                                        if (scrollDelta != 0f) {
                                            val atTop = gridState.firstVisibleItemIndex == 0 &&
                                                    gridState.firstVisibleItemScrollOffset == 0
                                            if (atTop && scrollDelta < 0f && !isRefreshing) {
                                                val target = (pullToRefreshAnim.value + (-scrollDelta * 0.15f)).coerceIn(0f, 1.5f)
                                                coroutineScope.launch {
                                                    pullToRefreshAnim.snapTo(target)
                                                    if (target >= 1.0f) {
                                                        executeRefresh()
                                                    }
                                                }
                                            } else if (pullToRefreshAnim.value > 0f) {
                                                val target = (pullToRefreshAnim.value + (scrollDelta * 0.15f)).coerceIn(0f, 1.5f)
                                                coroutineScope.launch {
                                                    pullToRefreshAnim.snapTo(target)
                                                }
                                            } else {
                                                gridState.dispatchRawDelta(scrollDelta * 64f)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var accumulatedZoom = 1f
                                var totalDragY = 0f
                                var isDragging = false
                                val isMouseOrUnknown = down.type == PointerType.Mouse || down.type == PointerType.Unknown

                                do {
                                    val event = awaitPointerEvent()

                                    // Multi-touch pinch zoom
                                    if (event.changes.size > 1) {
                                        val zoom = event.calculateZoom()
                                        accumulatedZoom *= zoom
                                        if (accumulatedZoom > 1.25f) {
                                            if (columnCount > 1) {
                                                columnCount--
                                            }
                                            accumulatedZoom = 1f
                                            event.changes.forEach { it.consume() }
                                        } else if (accumulatedZoom < 0.80f) {
                                            if (columnCount < 5) {
                                                columnCount++
                                            }
                                            accumulatedZoom = 1f
                                            event.changes.forEach { it.consume() }
                                        }
                                    } else if (isMouseOrUnknown && event.changes.size == 1) {
                                        // Waydroid mouse pointer drag
                                        val change = event.changes.first()
                                        if (change.pressed) {
                                            val deltaY = change.position.y - change.previousPosition.y
                                            totalDragY += deltaY

                                            if (!isDragging && kotlin.math.abs(totalDragY) > 8f) {
                                                isDragging = true
                                            }

                                            if (isDragging) {
                                                change.consume()
                                                val atTop = gridState.firstVisibleItemIndex == 0 &&
                                                        gridState.firstVisibleItemScrollOffset == 0
                                                val isPulling = pullToRefreshAnim.value > 0f || (atTop && deltaY > 0)

                                                if (isPulling && !isRefreshing) {
                                                    val target = (pullToRefreshAnim.value + deltaY / 300f).coerceIn(0f, 1.5f)
                                                    coroutineScope.launch {
                                                        pullToRefreshAnim.snapTo(target)
                                                    }
                                                } else {
                                                    gridState.dispatchRawDelta(-deltaY)
                                                }
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                if (isMouseOrUnknown && isDragging) {
                                    val currentFraction = pullToRefreshAnim.value
                                    if (currentFraction >= 1.0f && !isRefreshing) {
                                        executeRefresh()
                                    } else if (currentFraction > 0f) {
                                        coroutineScope.launch {
                                            pullToRefreshAnim.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    groupedGalleryItems.forEach { group ->
                        item(
                            key = "header_${group.key}",
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 6.dp)
                            )
                        }

                        items(group.items, key = { it.asset.fileId }) { item ->
                            var thumbnailBitmap by remember(item.asset.fileId) { mutableStateOf<Bitmap?>(null) }
                            var isLoadingThumbnail by remember(item.asset.fileId) { mutableStateOf(true) }

                            LaunchedEffect(item.asset.fileId, item.cryptoMapping?.thumbnailCiphertextHash, item.asset.thumbnailHash) {
                                isLoadingThumbnail = true
                                thumbnailBitmap = viewModel.loadThumbnail(item)
                                isLoadingThumbnail = false
                            }

                            val uploadStage = activeUploadStages[item.asset.fileId]

                            GalleryItemTile(
                                item = item,
                                thumbnail = thumbnailBitmap,
                                isLoading = isLoadingThumbnail,
                                uploadStage = uploadStage,
                                onClick = {
                                    // Fullscreen viewer deferred to next iteration
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
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
                            imageVector = LinkIcon,
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
