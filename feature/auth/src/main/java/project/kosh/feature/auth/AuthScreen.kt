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
import project.kosh.core.crypto.Bip39Sas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current

    var isRecoveryMode by remember { mutableStateOf(false) }
    var recoveryPassphrase by remember { mutableStateOf("") }

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
                    onRegisterSuccess()
                } else {
                    onAuthSuccess()
                }
            }
            else -> {}
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
                                    contentDescription = "Security Vault",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "KOSH",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
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
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Active Server Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .clickable { onChangeServer() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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

                // Authentication Card Container
                if (!isRecoveryMode) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = "Passkey Authentication",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = "Passkey Authentication",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(4.dp))

                            // Error Banner
                            AnimatedVisibility(
                                visible = state is AuthUiState.Error,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                val errorMessage = (state as? AuthUiState.Error)?.message ?: ""
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    ),
                                    shape = RoundedCornerShape(12.dp)
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
                                        .padding(vertical = 16.dp),
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
                                }
                            }

                            Spacer(Modifier.height(24.dp))

                            val isSubmitting = state is AuthUiState.Loading

                            // Primary Action: Sign in with Passkey
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
                                Text("Sign In with Passkey", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }

                            Spacer(Modifier.height(14.dp))

                            // Secondary Action: Create Account
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
                                Text("Create Account", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            }

                            Spacer(Modifier.height(20.dp))

                            HorizontalDivider()

                            Spacer(Modifier.height(12.dp))

                            // Recovery Link
                            TextButton(
                                onClick = {
                                    viewModel.resetState()
                                    isRecoveryMode = true
                                },
                                enabled = !isSubmitting
                            ) {
                                Text(
                                    text = "Can't use passkey? Recover with Passphrase",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                } else {
                    // Recovery View
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = {
                                    isRecoveryMode = false
                                    viewModel.resetState()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Back to Passkey Login"
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Account Recovery",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = "Enter your 24-word recovery phrase to restore access to your account and unwrap your Master Vault Key.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Error Banner
                            AnimatedVisibility(
                                visible = state is AuthUiState.Error,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                val errorMessage = (state as? AuthUiState.Error)?.message ?: ""
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    ),
                                    shape = RoundedCornerShape(12.dp)
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
                                        .padding(vertical = 16.dp),
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
                                }
                            }

                            Spacer(Modifier.height(20.dp))

                            val isSubmitting = state is AuthUiState.Loading

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
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                supportingText = {
                                    val trimmed = recoveryPassphrase.trim()
                                    if (trimmed.isNotEmpty()) {
                                        val words = trimmed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
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

                            Spacer(Modifier.height(20.dp))

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
                                Text("Recover Account & Unlock Vault", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }

                            Spacer(Modifier.height(14.dp))

                            TextButton(
                                onClick = {
                                    isRecoveryMode = false
                                    viewModel.resetState()
                                },
                                enabled = !isSubmitting,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Back to Passkey Sign-in",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Vault Unlock Modal Dialog (for Passkey login without cached key)
    val unlockRequiredState = state as? AuthUiState.VaultUnlockRequired
    if (unlockRequiredState != null) {
        var unlockPassphrase by remember { mutableStateOf("") }
        var unlockVisible by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { viewModel.cancelVaultUnlock() },
            title = {
                Text("Unlock Master Vault Key", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        text = "Passkey biometric verification succeeded. To decrypt and unwrap your vault key on this new device, enter your 24-word recovery phrase.",
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
                        placeholder = { Text("24-word recovery phrase or passphrase") },
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.unlockVault(unlockRequiredState.wrappedVaultKey, unlockPassphrase)
                    },
                    enabled = unlockPassphrase.isNotBlank() && !unlockRequiredState.isUnlocking
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
                        Text("Unlock Vault")
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


}
