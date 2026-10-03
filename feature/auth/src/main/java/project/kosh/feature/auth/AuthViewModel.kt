package project.kosh.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import project.kosh.core.auth.AuthRepository
import project.kosh.core.crypto.IdentityManager
import project.kosh.core.model.NetworkResult
import javax.inject.Inject


/**
 * ViewModel managing the business logic and UI state transitions for user authentication.
 *
 * Implements Unidirectional Data Flow (UDF) by exposing state through an immutable [StateFlow]
 * ([state]) while receiving user intents via [login] and [register] methods.
 *
 * ### Threading Architecture & Main Looper Protection
 * Computing Argon2id key derivation via [IdentityManager.deriveCredentials] requires allocating
 * 64 MiB of working RAM and performing 3 iterations of memory-hard hashing. On modern mobile processors,
 * this consumes between 300ms and 800ms of sustained CPU time.
 *
 * Executing this calculation directly within `viewModelScope.launch` would run on Android's Main
 * Looper thread, freezing user interface touch events, dropping frames, and risking Application
 * Not Responding (ANR) dialogs. This ViewModel strictly offloads key derivation to [defaultDispatcher]
 * ([Dispatchers.Default]) before delegating network requests to [AuthRepository].
 *
 * @param authRepository Domain repository orchestrating PoW challenges and server communication.
 * @param identityManager Cryptographic service responsible for Argon2id and SHA-256 derivations.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val identityManager: IdentityManager
) : ViewModel() {

    /**
     * Coroutine dispatcher used for CPU-intensive cryptographic derivations.
     * Defaults to [Dispatchers.Default]; exposed as internal for test overrides.
     */
    internal var defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)

    /**
     * Observable stream of discrete authentication UI states.
     */
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /**
     * Initiates the login authentication workflow.
     *
     * Sets state to [AuthUiState.Loading], derives credentials on [defaultDispatcher],
     * and delegates to [AuthRepository.login]. Updates [state] to [AuthUiState.Success]
     * on valid credentials, or [AuthUiState.Error] on failure.
     *
     * @param username Plaintext account username.
     * @param passphrase Plaintext account password.
     */
    fun login(username: String, passphrase: String) =
        viewModelScope.launch {
            _state.value = AuthUiState.Loading

            try {
                val credentials = withContext(defaultDispatcher) {
                    identityManager.deriveCredentials(username, passphrase)
                }

                when (val result = authRepository.login(credentials)) {
                    is NetworkResult.Success -> {
                        _state.value = AuthUiState.Success
                    }

                    is NetworkResult.Failure -> {
                        _state.value = AuthUiState.Error(result.userMessage)
                    }
                }
            } catch (e: Exception) {
                _state.value = AuthUiState.Error("Unexpected error: ${e.message}")
            }
        }

    /**
     * Initiates the new user registration workflow.
     *
     * Sets state to [AuthUiState.Loading], derives credentials on [defaultDispatcher],
     * and executes [AuthRepository.register]. Updates [state] to [AuthUiState.Success]
     * upon successful account creation, or [AuthUiState.Error] on failure.
     *
     * @param username Plaintext account username.
     * @param passphrase Plaintext account password.
     */
    fun register(username: String, passphrase: String) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            try {
                val credentials = withContext(defaultDispatcher) {
                    identityManager.deriveCredentials(username, passphrase)
                }

                when (val result = authRepository.register(credentials)) {
                    is NetworkResult.Success -> {
                        _state.value = AuthUiState.Success
                    }
                    is NetworkResult.Failure -> {
                        _state.value = AuthUiState.Error(result.userMessage)
                    }
                }
            } catch (e: Exception) {
                _state.value = AuthUiState.Error("Unexpected Error: ${e.message}")
            }
        }
    }
}
