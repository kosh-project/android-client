package project.kosh.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import project.kosh.core.auth.AuthRepository
import project.kosh.core.crypto.IdentityManager
import javax.inject.Inject


@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val identityManager: IdentityManager
) : ViewModel() {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun login(username: String, passphrase: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = AuthUiState.Loading

            try {
                val credentials = identityManager.deriveCredentials(username, passphrase)
                if (authRepository.login(credentials)) {
                    _state.value = AuthUiState.Success
                } else {
                    _state.value = AuthUiState.Error("Invalid Credentials or user doesn't exist.")
                }
            } catch (e: Exception) {
                _state.value = AuthUiState.Error("Network failure: ${e.message}")
            }
        }
    }

    fun register(username: String, passphrase: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = AuthUiState.Loading
            try {
                val credentials = identityManager.deriveCredentials(username, passphrase)
                if (authRepository.register(credentials)) {
                    _state.value = AuthUiState.Success
                } else {
                    _state.value = AuthUiState.Error("Registeration failed (username may already taken)")
                }
            } catch (e : Exception) {
                _state.value = AuthUiState.Error("Network failure : ${e.message}")
            }
        }
    }
}
