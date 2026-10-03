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


@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val identityManager: IdentityManager
) : ViewModel() {

    internal var defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

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
