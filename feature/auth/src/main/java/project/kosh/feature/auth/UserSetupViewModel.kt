package project.kosh.feature.auth

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import project.kosh.core.auth.PasskeyAuthManager
import project.kosh.core.auth.PasskeyRegisterResult
import project.kosh.core.model.NetworkResult
import javax.inject.Inject

/**
 * State representing the progression of passkey enrollment during device setup.
 */
sealed interface PasskeyEnrollmentState {
    data object Idle : PasskeyEnrollmentState
    data object Enrolling : PasskeyEnrollmentState
    data object Enrolled : PasskeyEnrollmentState
    data class Error(val message: String) : PasskeyEnrollmentState
}

/**
 * ViewModel managing the post-registration device setup and biometric onboarding workflow.
 *
 * @param passkeyAuthManager FIDO2 / WebAuthn coordinator for enrolling platform authenticators.
 */
@HiltViewModel
class UserSetupViewModel @Inject constructor(
    private val passkeyAuthManager: PasskeyAuthManager
) : ViewModel() {

    private val _deviceNickname = MutableStateFlow(getDefaultDeviceName())
    val deviceNickname: StateFlow<String> = _deviceNickname.asStateFlow()

    private val _enrollmentState = MutableStateFlow<PasskeyEnrollmentState>(PasskeyEnrollmentState.Idle)
    val enrollmentState: StateFlow<PasskeyEnrollmentState> = _enrollmentState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val listResult = passkeyAuthManager.listPasskeys()
                if (listResult is NetworkResult.Success && listResult.data.isNotEmpty()) {
                    _enrollmentState.value = PasskeyEnrollmentState.Enrolled
                }
            } catch (_: Exception) {
                // Ignore initial list error
            }
        }
    }

    fun updateNickname(newName: String) {
        _deviceNickname.value = newName
    }

    /**
     * Enrolls a biometric passkey for this client using the device nickname.
     *
     * @param activityContext Android activity context required for platform credential prompt.
     */
    fun enrollPasskey(activityContext: Context) {
        viewModelScope.launch {
            _enrollmentState.value = PasskeyEnrollmentState.Enrolling
            val nickname = _deviceNickname.value.trim().ifEmpty { getDefaultDeviceName() }
            when (val result = passkeyAuthManager.registerPasskey(activityContext, nickname)) {
                is PasskeyRegisterResult.Success -> {
                    _enrollmentState.value = PasskeyEnrollmentState.Enrolled
                }
                is PasskeyRegisterResult.Cancelled -> {
                    _enrollmentState.value = PasskeyEnrollmentState.Idle
                }
                is PasskeyRegisterResult.Error -> {
                    _enrollmentState.value = PasskeyEnrollmentState.Error(result.message)
                }
            }
        }
    }

    private fun getDefaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val combined = when {
            model.isEmpty() && manufacturer.isEmpty() -> "Android Device"
            manufacturer.isEmpty() -> model
            model.isEmpty() -> manufacturer
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
        return combined.ifBlank { "Android Device" }
    }
}
