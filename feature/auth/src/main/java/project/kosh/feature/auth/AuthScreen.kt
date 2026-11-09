package project.kosh.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Share
import androidx.core.content.ContextCompat
import project.kosh.core.crypto.Bip39Sas
import project.kosh.core.auth.PairingState
import project.kosh.feature.auth.QrCodeGenerator
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current

    var isRecoveryMode by remember { mutableStateOf(false) }
    var isLinkingMode by remember { mutableStateOf(false) }
    var recoveryPassphrase by remember { mutableStateOf("") }
    var showScannerDialog by remember { mutableStateOf(false) }

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
                if (s.isNewRegistration) {
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

    val topBarTitle = when {
        isLinkingMode -> "Link Device"
        isRecoveryMode -> "Recover Account"
        hasStoredCredentials -> "Sign In"
        else -> "Vault Access"
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
                                    imageVector = when {
                                        isLinkingMode -> Icons.Default.Share
                                        isRecoveryMode -> Icons.Default.Lock
                                        else -> Icons.Default.AccountCircle
                                    },
                                    contentDescription = topBarTitle,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = topBarTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onChangeServer) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Server Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
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
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                // Active Server Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .padding(bottom = 20.dp)
                        .clickable { onChangeServer() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (serverConfig.useTls) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = serverConfig.baseUrl,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                            .padding(bottom = 16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
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
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = loadingText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }

                val isSubmitting = state is AuthUiState.Loading

                // Main Views: Linking Mode, Recovery Mode, or Standard Authentication
                when {
                    isLinkingMode -> {
                        // Device Linking View
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Link Device",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = "Link Device",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(6.dp))

                            if (hasStoredCredentials) {
                                Text(
                                    text = "Scan this code with your new device, or scan a pairing QR code from that device.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                Text(
                                    text = "Scan this code with your authorized device, or scan a pairing QR code from that device.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(Modifier.height(20.dp))

                            when (val pState = pairingState) {
                                is PairingState.Ready, is PairingState.AwaitingJoin -> {
                                    val (mailboxId, qrPayloadJson) = when (pState) {
                                        is PairingState.Ready -> Pair(pState.mailboxId, pState.qrPayloadJson)
                                        is PairingState.AwaitingJoin -> Pair(pState.mailboxId, pState.qrPayloadJson)
                                    }

                                    // Mailbox Crockford Code
                                    val formattedCode = if (mailboxId.length == 8) {
                                        "${mailboxId.take(4).uppercase()} - ${mailboxId.drop(4).uppercase()}"
                                    } else {
                                        mailboxId.uppercase()
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                clipboardManager.setText(AnnotatedString(mailboxId))
                                                Toast.makeText(context, "Pairing code copied", Toast.LENGTH_SHORT).show()
                                            }
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
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

                                    // QR Code Display
                                    val qrBitmap = remember(qrPayloadJson) {
                                        try {
                                            QrCodeGenerator.generate(qrPayloadJson, 400)
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }

                                    if (qrBitmap != null) {
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            shadowElevation = 2.dp,
                                            modifier = Modifier.size(220.dp)
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

                                    Spacer(Modifier.height(16.dp))

                                    val statusMessage = if (pState is PairingState.AwaitingJoin) {
                                        "Waiting for peer device to scan..."
                                    } else {
                                        "Waiting for authorization..."
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = statusMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                is PairingState.AwaitingFulfillment -> {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            text = "Transferring master vault key...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                else -> {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            text = "Preparing pairing session...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(24.dp))

                            // Action: Scan peer QR Code
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
                                    .height(56.dp),
                                shape = RoundedCornerShape(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Scan QR Code", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }

                            Spacer(Modifier.height(12.dp))

                            TextButton(
                                onClick = {
                                    isLinkingMode = false
                                    viewModel.cancelDevicePairing()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Text("Back", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                            }
                        }
                    }

                    isRecoveryMode -> {
                        // Recovery View
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Recover Account",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = "Recover Account",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(6.dp))

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
                                label = { Text("Recovery Phrase") },
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
                                shape = RoundedCornerShape(16.dp),
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

                            Spacer(Modifier.height(24.dp))

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.recoverWithPassphrase(recoveryPassphrase)
                                },
                                enabled = !isSubmitting && recoveryPassphrase.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(28.dp)
                            ) {
                                Text("Recover account", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }

                            Spacer(Modifier.height(12.dp))

                            TextButton(
                                onClick = {
                                    isRecoveryMode = false
                                    viewModel.resetState()
                                },
                                enabled = !isSubmitting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Text("Back", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                            }
                        }
                    }

                    else -> {
                        // Standard Clean Authentication Screen
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            KoshBrandLogo(size = 64.dp)

                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = "Kosh Vault",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(6.dp))

                            Text(
                                text = "Zero-Knowledge Personal Storage",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(32.dp))

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
                                        .height(56.dp),
                                    shape = RoundedCornerShape(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("Sign back in", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }

                                Spacer(Modifier.height(14.dp))

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
                                        .height(56.dp),
                                    shape = RoundedCornerShape(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("Link device", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                }

                                Spacer(Modifier.height(14.dp))

                                OutlinedButton(
                                    onClick = {
                                        focusManager.clearFocus()
                                        viewModel.registerWithPasskey(activityContext = context)
                                    },
                                    enabled = !isSubmitting,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    shape = RoundedCornerShape(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("Create account", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                }
                            } else {
                                // Fresh install / no credentials in Keystore: Primary action is "Link device"
                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        viewModel.resetState()
                                        isLinkingMode = true
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
                                    enabled = !isSubmitting,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    shape = RoundedCornerShape(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("Link device", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }

                                Spacer(Modifier.height(14.dp))

                                OutlinedButton(
                                    onClick = {
                                        focusManager.clearFocus()
                                        viewModel.registerWithPasskey(activityContext = context)
                                    },
                                    enabled = !isSubmitting,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    shape = RoundedCornerShape(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("Create account", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                }
                            }

                            Spacer(Modifier.height(28.dp))

                            HorizontalDivider(modifier = Modifier.fillMaxWidth(0.6f))

                            Spacer(Modifier.height(16.dp))

                            // Recovery Link - Placed as the last option with clean, minimal text
                            TextButton(
                                onClick = {
                                    viewModel.resetState()
                                    isRecoveryMode = true
                                },
                                enabled = !isSubmitting
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

    // Vault Unlock Modal Dialog
    val unlockRequiredState = state as? AuthUiState.VaultUnlockRequired
    if (unlockRequiredState != null) {
        var unlockPassphrase by remember { mutableStateOf("") }
        var unlockVisible by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { viewModel.cancelVaultUnlock() },
            title = {
                Text("Unlock vault", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        text = "Enter your 24-word recovery phrase to decrypt your vault.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (unlockRequiredState.errorMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = unlockRequiredState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = unlockPassphrase,
                        onValueChange = { unlockPassphrase = it },
                        label = { Text("Recovery Phrase") },
                        placeholder = { Text("24-word recovery phrase") },
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
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Password },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.unlockVault(unlockRequiredState.wrappedVaultKey, unlockPassphrase)
                    },
                    enabled = unlockPassphrase.isNotBlank() && !unlockRequiredState.isUnlocking,
                    shape = RoundedCornerShape(20.dp)
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
                        Text("Unlock")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.cancelVaultUnlock() },
                    enabled = !unlockRequiredState.isUnlocking
                ) {
                    Text("Cancel")
                }
            }
        )
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

