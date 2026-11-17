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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import project.kosh.core.auth.PairingState
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.model.ActivePairingSession
import project.kosh.core.network.PingResult
import project.kosh.feature.auth.QrCameraScannerDialog
import project.kosh.feature.auth.QrCodeGenerator
import project.kosh.feature.gallery.model.GalleryDateGroup
import project.kosh.feature.gallery.ui.FloatingUploadBar
import project.kosh.feature.gallery.ui.FolderSelectionBottomSheet
import project.kosh.feature.gallery.ui.GalleryItemTile
import project.kosh.feature.gallery.ui.GalleryScrollDirectionTracker
import project.kosh.feature.gallery.ui.PermissionRationaleDialog
import project.kosh.feature.gallery.ui.isGalleryFabExpanded
import project.kosh.feature.gallery.ui.isGalleryIslandVisible
import project.kosh.feature.gallery.ui.icons.FolderIcon
import project.kosh.feature.gallery.ui.icons.LinkIcon
import project.kosh.feature.gallery.ui.icons.LogoutIcon
import project.kosh.feature.gallery.ui.icons.MoreVertIcon
import project.kosh.feature.gallery.ui.icons.NetworkPingIcon
import project.kosh.feature.gallery.ui.icons.SettingsIcon
import project.kosh.feature.gallery.ui.icons.SyncIcon

private val ItemPlacementSpec = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow
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

    val groupedGalleryItems by viewModel.groupedGalleryItems.collectAsState()
    val isBatchUploading by viewModel.isBatchUploading.collectAsState()

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
    val scrollTracker = remember {
        GalleryScrollDirectionTracker(
            initialIndex = gridState.firstVisibleItemIndex,
            initialOffset = gridState.firstVisibleItemScrollOffset
        )
    }
    val isFabExpanded by remember {
        derivedStateOf {
            isGalleryFabExpanded(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow {
            Triple(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
                gridState.isScrollInProgress
            )
        }.collect { (index, offset, inProgress) ->
            scrollTracker.onScrollUpdate(index, offset, inProgress)
        }
    }

    var targetScrollDelta by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            GalleryFloatingActionButtonSection(
                viewModel = viewModel,
                isFabExpanded = isFabExpanded,
                onLaunchPicker = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                }
            )
        }
    ) { innerPadding ->
        var showTopMenu by remember { mutableStateOf(false) }
        val isIslandVisible by remember {
            derivedStateOf {
                isGalleryIslandVisible(
                    isScrollingDown = scrollTracker.isScrollingDown,
                    showTopMenu = showTopMenu,
                    firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
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
                .background(MaterialTheme.colorScheme.background),
            indicator = {}
        ) {
            // Overstretched Header Space containing M3 Loading Indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
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
            if (groupedGalleryItems.isEmpty() && !isBatchUploading) {
                // Empty Vault State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Empty Vault",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "No items yet",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = "Photos and documents you upload are encrypted and stored in your vault.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Select photos", fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = { viewModel.openFolderSelection() },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Icon(Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sync folders", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            } else {
                // Interactive Media Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columnCount),
                    state = gridState,
                    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 0.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Scroll) {
                                        val deltaY = event.changes.fold(0f) { acc, change ->
                                            acc + change.scrollDelta.y
                                        } * 64f

                                        if (deltaY != 0f) {
                                            targetScrollDelta += deltaY
                                            if (scrollJob?.isActive != true) {
                                                scrollJob = coroutineScope.launch {
                                                    while (kotlin.math.abs(targetScrollDelta) > 0.5f) {
                                                        val step = targetScrollDelta * 0.35f
                                                        targetScrollDelta -= step
                                                        val consumed = gridState.dispatchRawDelta(step)
                                                        if (kotlin.math.abs(consumed) < 0.1f) {
                                                            targetScrollDelta = 0f
                                                            break
                                                        }
                                                        withFrameNanos { }
                                                    }
                                                    targetScrollDelta = 0f
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var accumulatedZoom = 1f
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
                                    } else {
                                        accumulatedZoom = 1f
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                ) {
                    groupedGalleryItems.forEach { group ->
                        stickyHeader(
                            key = "header_${group.key}"
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = group.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(start = 16.dp, end = 96.dp, top = 8.dp, bottom = 8.dp)
                                )
                            }
                        }

                        items(
                            items = group.items,
                            key = { it.asset.fileId },
                            contentType = { if (it.asset.mediaType.startsWith("video/", ignoreCase = true)) "video" else "photo" }
                        ) { item ->
                            GalleryGridItem(
                                item = item,
                                viewModel = viewModel,
                                modifier = Modifier.animateItem(
                                    placementSpec = ItemPlacementSpec
                                )
                            )
                        }
                    }
                }
            }
        }
        }

        AnimatedVisibility(
            visible = isIslandVisible,
            enter = fadeIn(animationSpec = tween(durationMillis = 200)) + slideInVertically(
                initialOffsetY = { -it / 2 },
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
            ),
            exit = fadeOut(animationSpec = tween(durationMillis = 150)) + slideOutVertically(
                targetOffsetY = { -it / 2 },
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                shadowElevation = 4.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isSessionActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(7.dp)
                    ) {}

                    if (!activeUser.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = activeUser.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    Box {
                        IconButton(
                            onClick = { showTopMenu = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = MoreVertIcon,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
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
                    }
                }
            }
        }
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
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Done", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Retry pairing")
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                viewModel.stopHostPairing()
                                showLinkDeviceBottomSheet = false
                            },
                            shape = RoundedCornerShape(10.dp),
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
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
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
                                            text = "Pairing code",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = formattedCode,
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            letterSpacing = 2.sp,
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
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
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
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f * expiredOverlayAlpha),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
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
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Warning,
                                                    contentDescription = "Expired",
                                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            text = "Pairing session expired",
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
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.height(44.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Generate new code")
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
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text("Scan camera QR", fontSize = 13.sp)
                            }

                            OutlinedButton(
                                onClick = { showManualCodeEntry = !showManualCodeEntry },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text(if (showManualCodeEntry) "Hide code" else "Enter code", fontSize = 13.sp)
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
                                    label = { Text("8-character code") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { viewModel.fulfillReceiverCode(manualPairingCode) },
                                    enabled = manualPairingCode.trim().replace("-", "").replace(" ", "").length == 8,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(48.dp)
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

@Composable
private fun GalleryFloatingActionButtonSection(
    viewModel: GalleryViewModel,
    isFabExpanded: Boolean,
    onLaunchPicker: () -> Unit
) {
    val batchUploadState by viewModel.batchUploadState.collectAsState()
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        ExtendedFloatingActionButton(
            expanded = isFabExpanded,
            onClick = onLaunchPicker,
            icon = {
                Icon(Icons.Default.Add, contentDescription = "Upload media")
            },
            text = { Text("Upload", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) },
            shape = RoundedCornerShape(14.dp),
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 6.dp,
                pressedElevation = 8.dp,
                focusedElevation = 6.dp,
                hoveredElevation = 7.dp
            ),
            modifier = Modifier.padding(bottom = if (batchUploadState.isUploading) 10.dp else 0.dp)
        )

        FloatingUploadBar(
            batchState = batchUploadState,
            onDismiss = { viewModel.dismissBatchBar() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GalleryGridItem(
    item: MediaWithCryptoDetails,
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier
) {
    val cachedBitmap = remember(item.asset.fileId) { viewModel.getFromMemoryCache(item) }
    var thumbnailBitmap by remember(item.asset.fileId) { mutableStateOf(cachedBitmap) }
    var isSyncingRemote by remember(item.asset.fileId) { mutableStateOf(false) }

    if (thumbnailBitmap == null) {
        LaunchedEffect(item.asset.fileId) {
            // 32ms coroutine debounce: if user is flinging fast, item scrolls off-screen before 32ms,
            // cancelling this coroutine naturally without touching disk or crypto
            delay(32L)
            val needsRemoteDownload = !viewModel.hasDiskCache(item.asset.fileId) &&
                !viewModel.isInFlight(item.asset.fileId) &&
                (item.cryptoMapping?.thumbnailCiphertextHash != null || item.asset.thumbnailHash != null)

            if (needsRemoteDownload) {
                isSyncingRemote = true
            }
            try {
                thumbnailBitmap = viewModel.loadThumbnail(item)
            } finally {
                isSyncingRemote = false
            }
        }
    }

    val uploadStage by remember(item.asset.fileId) {
        viewModel.getUploadStage(item.asset.fileId)
    }.collectAsState(initial = null)

    GalleryItemTile(
        item = item,
        thumbnail = thumbnailBitmap,
        isSyncingRemote = isSyncingRemote,
        uploadStage = uploadStage,
        onClick = {
            // Fullscreen viewer deferred to next iteration
        },
        modifier = modifier
    )
}

