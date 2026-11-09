package project.kosh.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * Post-registration onboarding screen split into two uncluttered, breathing sub-steps:
 * - Sub-step 1 (Step 3 of 4): Device Label Setup with brief descriptions.
 * - Sub-step 2 (Step 4 of 4): Biometric Passkey Enrollment.
 *
 * Each sub-screen is tailored to fit entirely within the viewport without requiring scrolling.
 *
 * @param viewModel The Hilt-backed [UserSetupViewModel] orchestrating passkey enrollment.
 * @param onFinish Navigation callback to transition to the gallery screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSetupScreen(
    viewModel: UserSetupViewModel = hiltViewModel(),
    onFinish: () -> Unit
) {
    val nickname by viewModel.deviceNickname.collectAsState()
    val enrollmentState by viewModel.enrollmentState.collectAsState()
    val context = LocalContext.current

    var currentSubStep by rememberSaveable { mutableIntStateOf(1) }

    BackHandler {
        if (currentSubStep > 1) {
            currentSubStep--
        } else {
            onFinish()
        }
    }

    val topBarTitle = if (currentSubStep == 1) "Device Setup" else "Biometric Passkey"

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
                                    imageVector = if (currentSubStep == 1) Icons.Default.PhoneAndroid else Icons.Default.Fingerprint,
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
            Column(modifier = Modifier.fillMaxSize()) {
                // Playful Registration Progress Bar (Step 3 or Step 4 of 4)
                RegistrationProgressBar(
                    currentStep = if (currentSubStep == 1) 3 else 4,
                    totalSteps = 4
                )

                AnimatedContent(
                    targetState = currentSubStep,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> -width } + fadeOut()
                            )
                        } else {
                            (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> width } + fadeOut()
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "user_setup_substep"
                ) { step ->
                    if (step == 1) {
                        // Sub-step 1: Device Label (Fits in viewport without scrolling)
                        DeviceLabelSubStep(
                            nickname = nickname,
                            onNicknameChange = { viewModel.updateNickname(it) },
                            onContinue = { currentSubStep = 2 },
                            onSkip = { currentSubStep = 2 }
                        )
                    } else {
                        // Sub-step 2: Biometric Passkey Enrollment (Fits in viewport without scrolling)
                        BiometricPasskeySubStep(
                            enrollmentState = enrollmentState,
                            onEnroll = { viewModel.enrollPasskey(context) },
                            onFinish = onFinish
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sub-step 1: Device Label setup with concise, clear instructions fitting standard screens without scrolling.
 */
@Composable
private fun DeviceLabelSubStep(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            KoshBrandLogo(size = 56.dp)

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Welcome",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Choose a label for this device to identify it in your vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = nickname,
                onValueChange = onNicknameChange,
                label = { Text("Device Nickname") },
                placeholder = { Text("e.g. My Phone") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(Modifier.height(20.dp))

            // Shortened Security Notice
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Your vault is encrypted with a master key. Only you hold the recovery keys.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Action Buttons at Bottom
        Column(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onContinue,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Continue", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip for now", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Sub-step 2: Biometric Passkey enrollment fitting standard screens without scrolling.
 */
@Composable
private fun BiometricPasskeySubStep(
    enrollmentState: PasskeyEnrollmentState,
    onEnroll: () -> Unit,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Biometric Passkey",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Sign in instantly with your fingerprint or screen lock.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            when (enrollmentState) {
                is PasskeyEnrollmentState.Idle -> {
                    Button(
                        onClick = onEnroll,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Enroll Biometric Passkey", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }

                is PasskeyEnrollmentState.Enrolling -> {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Registering hardware passkey...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                is PasskeyEnrollmentState.Enrolled -> {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Enrolled",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Passkey Enrolled",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Hardware credential active and ready.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                is PasskeyEnrollmentState.Error -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
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
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = enrollmentState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onEnroll,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(26.dp)
                        ) {
                            Text("Try Again", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                    }
                }
            }
        }

        // Action Buttons at Bottom
        Column(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(
                    text = if (enrollmentState is PasskeyEnrollmentState.Enrolled) "Finish & Open Gallery" else "Continue to Gallery",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            if (enrollmentState !is PasskeyEnrollmentState.Enrolled) {
                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Skip for now",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
