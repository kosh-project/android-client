package project.kosh.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * Primary Jetpack Compose screen for user authentication and account registration.
 *
 * Implements Unidirectional Data Flow (UDF) by collecting the active [AuthUiState]
 * from [AuthViewModel.state] and dispatching user actions back to the ViewModel.
 *
 * Monitors state transitions via a [LaunchedEffect]: when authentication succeeds
 * ([AuthUiState.Success]), invokes [onAuthSuccess] to transition navigation to the main app destination.
 *
 * @param viewModel The Hilt-backed [AuthViewModel] managing authentication logic.
 * @param onAuthSuccess Navigation callback triggered when authentication completes successfully.
 */
@Composable
fun AuthScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    onAuthSuccess: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    var username by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is AuthUiState.Success) {
            onAuthSuccess()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Kosh Secure Storage", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(32.dp))

        when (val currentState = state) {
            is AuthUiState.Loading -> {
                CircularProgressIndicator()
            }
            is AuthUiState.Error -> {
                Text(currentState.message, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                AuthButtons(viewModel, username, passphrase)
            }
            else -> {
                AuthButtons(viewModel, username, passphrase)
            }
        }
    }
}

/**
 * Renders the Login and Register action button controls.
 *
 * Buttons are enabled only when both [username] and [pass] contain non-blank text.
 *
 * @param viewModel The [AuthViewModel] receiving button click events.
 * @param username Current text entered in the username field.
 * @param pass Current text entered in the passphrase field.
 */
@Composable
fun AuthButtons(
    viewModel: AuthViewModel,
    username: String,
    pass: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Button(
            onClick = { viewModel.login(username, pass) },
            enabled = username.isNotBlank() && pass.isNotBlank()
        ) {
            Text("Login")
        }

        OutlinedButton(
            onClick = { viewModel.register(username, pass) },
            enabled = username.isNotBlank() && pass.isNotBlank()
        ) {
            Text("Register")
        }
    }
}