package project.kosh.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import project.kosh.core.crypto.Bip39Sas
import project.kosh.core.auth.PairingState
import project.kosh.core.model.ActivePairingSession
import project.kosh.feature.auth.QrCodeGenerator
import project.kosh.feature.auth.ui.icons.LinkIcon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import project.kosh.core.network.DiscoveredServer
import project.kosh.core.network.ServerConfig

/**
 * Modern Jetpack Compose screen for user authentication, registration, and passkey login.
 *
 * Implements Unidirectional Data Flow (UDF) by collecting [AuthUiState] and dispatching
 * user actions to [AuthViewModel].
 *
 * @param viewModel The Hilt-backed [AuthViewModel] orchestrating authentication logic.
 * @param onAuthSuccess Navigation callback triggered when existing user login completes.
 * @param onRegisterSuccess Navigation callback triggered when a new user finishes registration.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    onChangeServer: () -> Unit = {},
    onAuthSuccess: () -> Unit,
    onRegisterSuccess: () -> Unit = onAuthSuccess
) {
    val state by viewModel.state.collectAsState()
    val serverConfig by viewModel.serverConfig.collectAsState()
    val hasStoredCredentials by viewModel.hasStoredCredentials.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    val activePairingSession by viewModel.activePairingSession.collectAsState()

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current

    val unlockRequiredState = state as? AuthUiState.VaultUnlockRequired
    val isVaultUnlockIntent = unlockRequiredState != null

    var isRecoveryMode by remember { mutableStateOf(false) }
    var isLinkingMode by remember { mutableStateOf(false) }
    var isVaultUnlockSession by remember { mutableStateOf(false) }
    var recoveryPassphrase by remember { mutableStateOf("") }
    var showScannerDialog by remember { mutableStateOf(false) }

    var showManualPhraseInput by remember { mutableStateOf(false) }
    var showManualCodeEntry by remember { mutableStateOf(false) }
    var manualPairingCode by remember { mutableStateOf("") }
    var unlockPassphrase by remember { mutableStateOf("") }
    var unlockVisible by remember { mutableStateOf(false) }

    var activeMailboxId by remember { mutableStateOf<String?>(null) }
    var activeQrPayloadJson by remember { mutableStateOf<String?>(null) }
    var isSessionExpired by remember(activePairingSession) { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshStoredCredentials()
    }

    LaunchedEffect(state) {
        if (state is AuthUiState.VaultUnlockRequired) {
            isVaultUnlockSession = true
            if (!isLinkingMode) {
                isLinkingMode = true
                viewModel.startDevicePairing()
            }
        }
    }

    LaunchedEffect(pairingState) {
        when (val p = pairingState) {
            is PairingState.Ready -> {
                activeMailboxId = p.mailboxId
                activeQrPayloadJson = p.qrPayloadJson
            }
            is PairingState.AwaitingJoin -> {
                activeMailboxId = p.mailboxId
                activeQrPayloadJson = p.qrPayloadJson
            }
            is PairingState.Error -> {
                val err = p.message.lowercase()
                if (err.contains("expire") || err.contains("timed out") || err.contains("404") || err.contains("401")) {
                    isSessionExpired = true
                }
            }
            null -> {
                activeMailboxId = null
                activeQrPayloadJson = null
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
        label = "mailboxBlur"
    )

    val expiredOverlayAlpha by animateFloatAsState(
        targetValue = if (isSessionExpired) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "expiredOverlayAlpha"
    )

    BackHandler(isLinkingMode || isRecoveryMode) {
        if (isLinkingMode) {
            isLinkingMode = false
            showManualPhraseInput = false
            showManualCodeEntry = false
            manualPairingCode = ""
            if (isVaultUnlockIntent || isVaultUnlockSession) {
                isVaultUnlockSession = false
                viewModel.cancelVaultUnlock()
            }
            viewModel.cancelDevicePairing()
        } else if (isRecoveryMode) {
            isRecoveryMode = false
            viewModel.resetState()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showScannerDialog = true
        } else {
            Toast.makeText(
                context,
                "Camera permission is required to scan pairing QR code",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val successState = state as? AuthUiState.Success
    if (successState != null && successState.isNewRegistration && successState.recoveryWords != null) {
        RecoveryBackupScreen(
            words = successState.recoveryWords,
            serverHost = serverConfig.baseUrl,
            onProceed = onRegisterSuccess
        )
        return
    }

    LaunchedEffect(state) {
        when (val s = state) {
            is AuthUiState.Success -> {
                if (s.isNewRegistration && !isVaultUnlockSession) {
                    if (s.recoveryWords == null) {
                        onRegisterSuccess()
                    }
                } else {
                    onAuthSuccess()
                }
            }
            else -> {}
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Sleek Top Header Row with status bar inset: Back on left (if linking/recovery), Server Pill on right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLinkingMode || isRecoveryMode) {
                        IconButton(
                            onClick = {
                                if (isLinkingMode) {
                                    isLinkingMode = false
                                    showManualPhraseInput = false
                                    showManualCodeEntry = false
                                    manualPairingCode = ""
                                    if (isVaultUnlockIntent || isVaultUnlockSession) {
                                        isVaultUnlockSession = false
                                        viewModel.cancelVaultUnlock()
                                    }
                                    viewModel.cancelDevicePairing()
                                } else if (isRecoveryMode) {
                                    isRecoveryMode = false
                                    viewModel.resetState()
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    // Compact Clickable Server Address Pill on Top Right
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                        modifier = Modifier.clickable { onChangeServer() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (serverConfig.useTls) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(7.dp)
                            ) {}
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = serverConfig.baseUrl.removePrefix("https://").removePrefix("http://"),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Main Content Area with 3-tier structure
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    // Playful Registration Progress Bar during active account creation (Step 1 of 4)
                    val isRegistering = (state as? AuthUiState.Loading)?.message?.let {
                        it.contains("vault", ignoreCase = true) ||
                        it.contains("recovery", ignoreCase = true) ||
                        it.contains("account", ignoreCase = true) ||
                        it.contains("PoW", ignoreCase = true)
                    } ?: false

                    if (isRegistering) {
                        RegistrationProgressBar(
                            currentStep = 1,
                            totalSteps = 4,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    // Error Banner
                    AnimatedVisibility(
                        visible = state is AuthUiState.Error,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        val errorMessage = (state as? AuthUiState.Error)?.message ?: ""
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.resetState() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Loading State Indicator
                    if (state is AuthUiState.Loading) {
                        val loadingText = (state as AuthUiState.Loading).message
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = loadingText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    val isSubmitting = state is AuthUiState.Loading

                    // Main Views: Linking Mode, Recovery Mode, or Standard Authentication
                    when {
                        isLinkingMode -> {
                            // Device Linking View
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isVaultUnlockIntent) Icons.Default.Lock else LinkIcon,
                                            contentDescription = if (isVaultUnlockIntent) "Retrieve vault key" else "Link device",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = if (isVaultUnlockIntent) "Retrieve Vault Key" else "Link Device",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(Modifier.height(4.dp))

                                Text(
                                    text = when {
                                        isVaultUnlockIntent -> "Your passkey was verified. Link with an authorized device to securely retrieve your Master Vault Key, or enter your recovery phrase below."
                                        hasStoredCredentials -> "Scan this code with your new device, or scan a pairing QR code from that device."
                                        else -> "Scan this code with your authorized device, or scan a pairing QR code from that device."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(Modifier.height(14.dp))

                                val currentMailboxId = activePairingSession?.mailboxId ?: activeMailboxId
                                val currentQrJson = activePairingSession?.qrPayloadJson ?: activeQrPayloadJson

                                val isError = pairingState is PairingState.Error && !isSessionExpired

                                if (isError) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
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
                                                    contentDescription = "Error",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            text = "Pairing session error",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = (pairingState as PairingState.Error).message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(Modifier.height(14.dp))
                                        Button(
                                            onClick = { viewModel.startDevicePairing() },
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Retry")
                                        }
                                    }
                                } else if (currentMailboxId != null && currentQrJson != null) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // Mailbox Code and QR Code - with animated blur upon expiration
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .blur(blurRadius),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            // Mailbox Crockford Code
                                            val formattedCode = if (currentMailboxId.length == 8) {
                                                "${currentMailboxId.take(4).uppercase()} - ${currentMailboxId.drop(4).uppercase()}"
                                            } else {
                                                currentMailboxId.uppercase()
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable(enabled = !isSessionExpired) {
                                                        clipboardManager.setText(AnnotatedString(currentMailboxId))
                                                        Toast.makeText(context, "Pairing code copied", Toast.LENGTH_SHORT).show()
                                                    }
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(12.dp),
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
                                                        style = MaterialTheme.typography.titleLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Monospace,
                                                        letterSpacing = 2.sp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }

                                            Spacer(Modifier.height(16.dp))

                                            // QR Code Display (Compact 210dp surface)
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
                                                    color = androidx.compose.ui.graphics.Color.White,
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                                    shadowElevation = 1.dp,
                                                    modifier = Modifier.size(200.dp)
                                                ) {
                                                    Box(
                                                        contentAlignment = Alignment.Center,
                                                        modifier = Modifier.padding(10.dp)
                                                    ) {
                                                        Image(
                                                            bitmap = qrBitmap,
                                                            contentDescription = "Pairing QR Code",
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(Modifier.height(12.dp))

                                            val statusMessage = when (pairingState) {
                                                is PairingState.AwaitingJoin -> "Waiting for peer device to scan..."
                                                is PairingState.AwaitingFulfillment -> "Waiting for authorized device to scan..."
                                                is PairingState.Success -> "Device authorized! Unlocking vault..."
                                                else -> "Waiting for authorization..."
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = statusMessage,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        // Expired Overlay with animated fade-in & Re-generate Mailbox Button
                                        if (expiredOverlayAlpha > 0f) {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f * expiredOverlayAlpha),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                                                shadowElevation = 2.dp,
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
                                                                imageVector = Icons.Default.HourglassBottom,
                                                                contentDescription = "Expired",
                                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                    Spacer(Modifier.height(10.dp))
                                                    Text(
                                                        text = "Pairing session expired",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.SemiBold,
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
                                                            viewModel.startDevicePairing()
                                                        },
                                                        shape = RoundedCornerShape(10.dp),
                                                        modifier = Modifier.height(44.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Refresh,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            text = "Create new mailbox",
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            text = "Preparing pairing session...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(Modifier.height(16.dp))

                                // Manual code entry section if activated
                                if (showManualCodeEntry) {
                                    Spacer(Modifier.height(16.dp))
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(14.dp)
                                            )
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Enter 8-character pairing code",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = manualPairingCode,
                                            onValueChange = { if (it.length <= 10) manualPairingCode = it.uppercase() },
                                            label = { Text("Pairing code") },
                                            placeholder = { Text("e.g. ABCD-EFGH") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(onClick = {
                                                showManualCodeEntry = false
                                                manualPairingCode = ""
                                            }) {
                                                Text("Cancel")
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Button(
                                                onClick = {
                                                    viewModel.linkWithPairingCode(manualPairingCode)
                                                    showManualCodeEntry = false
                                                    manualPairingCode = ""
                                                },
                                                enabled = manualPairingCode.replace("-", "").trim().length == 8,
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("Join")
                                            }
                                        }
                                    }
                                }

                                // Secondary option for Vault Unlock: Enter 24-word recovery phrase if activated
                                if (unlockRequiredState != null && showManualPhraseInput) {
                                    Spacer(Modifier.height(16.dp))
                                    HorizontalDivider(modifier = Modifier.fillMaxWidth(0.6f))
                                    Spacer(Modifier.height(16.dp))

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(14.dp)
                                            )
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Enter 24-word recovery phrase",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (unlockRequiredState.errorMessage != null) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = unlockRequiredState.errorMessage,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        OutlinedTextField(
                                            value = unlockPassphrase,
                                            onValueChange = { unlockPassphrase = it },
                                            label = { Text("Recovery phrase") },
                                            placeholder = { Text("24 words separated by spaces") },
                                            isError = unlockRequiredState.errorMessage != null,
                                            enabled = !unlockRequiredState.isUnlocking,
                                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                            trailingIcon = {
                                                TextButton(
                                                    onClick = { unlockVisible = !unlockVisible },
                                                    enabled = !unlockRequiredState.isUnlocking
                                                ) {
                                                    Text(if (unlockVisible) "Hide" else "Show")
                                                }
                                            },
                                            visualTransformation = if (unlockVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                            minLines = 2,
                                            maxLines = 4,
                                            singleLine = false,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .semantics { contentType = ContentType.Password },
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Button(
                                            onClick = {
                                                focusManager.clearFocus()
                                                viewModel.unlockVault(unlockRequiredState.wrappedVaultKey, unlockPassphrase)
                                            },
                                            enabled = unlockPassphrase.isNotBlank() && !unlockRequiredState.isUnlocking,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(50.dp),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            if (unlockRequiredState.isUnlocking) {
                                                CircularProgressIndicator(
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(18.dp),
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text("Unlocking...")
                                            } else {
                                                Text("Unlock vault")
                                            }
                                        }
                                    }
                                }
                            }

                            // Pinned Action Buttons at Bottom
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.CAMERA
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasPermission) {
                                            showScannerDialog = true
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(
                                        imageVector = LinkIcon,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Scan QR code", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                }

                                if (!showManualCodeEntry) {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { showManualCodeEntry = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text("Enter code from authorized device", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    }
                                }

                                if (unlockRequiredState != null && !showManualPhraseInput) {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { showManualPhraseInput = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Enter recovery phrase instead", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                TextButton(
                                    onClick = {
                                        isLinkingMode = false
                                        showManualPhraseInput = false
                                        showManualCodeEntry = false
                                        manualPairingCode = ""
                                        if (isVaultUnlockIntent || isVaultUnlockSession) {
                                            isVaultUnlockSession = false
                                            viewModel.cancelVaultUnlock()
                                        }
                                        viewModel.cancelDevicePairing()
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                ) {
                                    Text(if (isVaultUnlockIntent || isVaultUnlockSession) "Cancel" else "Back", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    isRecoveryMode -> {
                        // Recovery View
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Recover account",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(18.dp))

                                Text(
                                    text = "Recover account",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = "Enter your 24-word recovery phrase to restore access.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(Modifier.height(24.dp))

                                OutlinedTextField(
                                    value = recoveryPassphrase,
                                    onValueChange = { recoveryPassphrase = it },
                                    label = { Text("Recovery phrase") },
                                    placeholder = { Text("Enter 24 words separated by spaces") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Lock, contentDescription = "Recovery Passphrase Icon")
                                    },
                                    trailingIcon = {
                                        if (recoveryPassphrase.isNotEmpty()) {
                                            IconButton(onClick = { recoveryPassphrase = "" }) {
                                                Icon(Icons.Default.Clear, contentDescription = "Clear Phrase")
                                            }
                                        }
                                    },
                                    minLines = 3,
                                    maxLines = 5,
                                    singleLine = false,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            focusManager.clearFocus()
                                            if (recoveryPassphrase.isNotBlank()) {
                                                viewModel.recoverWithPassphrase(recoveryPassphrase)
                                            }
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics { contentType = ContentType.Password },
                                    shape = RoundedCornerShape(14.dp),
                                    supportingText = {
                                        val trimmed = recoveryPassphrase.trim()
                                        if (trimmed.isNotEmpty()) {
                                            val words = Bip39Sas.extractWords(recoveryPassphrase)
                                            val wordCount = words.size
                                            if (wordCount in 1..23 && wordCount != 12) {
                                                Text(
                                                    text = "Entered $wordCount of 24 words",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            } else if (wordCount == 24 || wordCount == 12) {
                                                val invalid = words.filter { !Bip39Sas.isValidWord(it) }
                                                if (invalid.isEmpty()) {
                                                    Text(
                                                        text = "All $wordCount words recognized",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                } else {
                                                    Text(
                                                        text = "Unrecognized word: ${invalid.joinToString(", ")}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    text = "Entered $wordCount words (standard phrase is 24 words)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                )
                            }

                            // Pinned Action Button at Bottom
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        viewModel.recoverWithPassphrase(recoveryPassphrase)
                                    },
                                    enabled = !isSubmitting && recoveryPassphrase.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("Recover account", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                }
                            }
                        }
                    }

                    else -> {
                        // Standard Clean Authentication Screen
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Consumable Brand Hero centered with weight(1f)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                KoshBrandLogo(size = 64.dp)

                                Spacer(Modifier.height(16.dp))

                                Text(
                                    text = "Kosh Vault",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = "Zero-knowledge personal storage",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }

                            // Pinned Thumb-Friendly Action Dock at Bottom
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (hasStoredCredentials) {
                                    // Device has credentials in Keystore: Show "Sign back in" as primary action
                                    Button(
                                        onClick = {
                                            focusManager.clearFocus()
                                            viewModel.loginWithPasskey(activityContext = context)
                                        },
                                        enabled = !isSubmitting,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AccountCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text("Sign back in", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    FilledTonalButton(
                                        onClick = {
                                            focusManager.clearFocus()
                                            viewModel.resetState()
                                            isLinkingMode = true
                                            viewModel.startDevicePairing()
                                        },
                                        enabled = !isSubmitting,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = LinkIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Link device", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    OutlinedButton(
                                        onClick = {
                                            focusManager.clearFocus()
                                            viewModel.registerWithPasskey(activityContext = context)
                                        },
                                        enabled = !isSubmitting,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Create account", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    }
                                } else {
                                    // Fresh install / no credentials in Keystore: Primary action is "Link device"
                                    Button(
                                        onClick = {
                                            focusManager.clearFocus()
                                            viewModel.resetState()
                                            isLinkingMode = true
                                            viewModel.startDevicePairing()
                                        },
                                        enabled = !isSubmitting,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = LinkIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text("Link device", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    OutlinedButton(
                                        onClick = {
                                            focusManager.clearFocus()
                                            viewModel.registerWithPasskey(activityContext = context)
                                        },
                                        enabled = !isSubmitting,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Create account", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                // Recovery Link - Placed as the last option with clean, minimal text
                                TextButton(
                                    onClick = {
                                        viewModel.resetState()
                                        isRecoveryMode = true
                                    },
                                    enabled = !isSubmitting,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                ) {
                                    Text(
                                        text = "Recover account",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}



    // Embedded Camera QR Scanner Dialog for peer pairing
    if (showScannerDialog) {
        QrCameraScannerDialog(
            onDismiss = { showScannerDialog = false },
            onScanned = { _, rawValue ->
                showScannerDialog = false
                if (rawValue != null) {
                    viewModel.linkWithScannedQr(rawValue)
                }
            }
        )
    }
}

