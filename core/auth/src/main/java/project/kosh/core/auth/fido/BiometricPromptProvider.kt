package project.kosh.core.auth.fido

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Interface abstracting native Android biometric prompt presentations.
 *
 * Encapsulates the UI lifecycle and callback mechanisms of [androidx.biometric.BiometricPrompt]
 * into a structured suspending coroutine.
 */
interface BiometricPromptProvider {
    /**
     * Presents the platform biometric authentication dialog.
     *
     * @param activity The host [FragmentActivity] required by AndroidX Biometric.
     * @param title Title text for the biometric prompt sheet.
     * @param subtitle Subtitle text explaining the authentication purpose.
     * @param cryptoObject Optional cryptographic signature or cipher object to authorize.
     * @return [BiometricPrompt.AuthenticationResult] upon successful biometric verification.
     * @throws BiometricCancelledException If the user cancels or dismisses the prompt.
     * @throws FidoAuthenticationException If a platform error or lockout occurs.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cryptoObject: BiometricPrompt.CryptoObject?
    ): BiometricPrompt.AuthenticationResult
}

/**
 * Production implementation of [BiometricPromptProvider] presenting AndroidX Biometric prompts.
 *
 * Supports both [BiometricManager.Authenticators.BIOMETRIC_STRONG] and
 * [BiometricManager.Authenticators.DEVICE_CREDENTIAL] to allow device lock screen
 * PIN/pattern/password fallback on devices lacking biometric hardware (such as Waydroid or emulators).
 */
@Singleton
class DefaultBiometricPromptProvider @Inject constructor() : BiometricPromptProvider {

    override suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cryptoObject: BiometricPrompt.CryptoObject?
    ): BiometricPrompt.AuthenticationResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val biometricManager = BiometricManager.from(activity)

            val canBoth = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) == BiometricManager.BIOMETRIC_SUCCESS
            val canDeviceCredential = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) == BiometricManager.BIOMETRIC_SUCCESS
            val canStrong = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) == BiometricManager.BIOMETRIC_SUCCESS

            val allowDeviceCredential = canBoth || canDeviceCredential

            val promptBuilder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)

            val authenticators = if (allowDeviceCredential) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                } else {
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                }
            } else {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            }

            promptBuilder.setAllowedAuthenticators(authenticators)

            val isDeviceCredentialAllowed = (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL) != 0
            if (!isDeviceCredentialAllowed) {
                promptBuilder.setNegativeButtonText("Cancel")
            }

            val promptInfo = promptBuilder.build()

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) {
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_CANCELED
                        ) {
                            continuation.resumeWithException(
                                BiometricCancelledException(errString.toString())
                            )
                        } else {
                            val message = if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ||
                                errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                                errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT
                            ) {
                                "Screen lock or biometrics required. Please configure a device PIN, pattern, or password in system settings."
                            } else {
                                "Authentication error ($errorCode): $errString"
                            }
                            continuation.resumeWithException(
                                FidoAuthenticationException(message)
                            )
                        }
                    }
                }

                override fun onAuthenticationFailed() {
                    // Biometric attempt rejected. Prompt remains visible allowing retry.
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)
            if (cryptoObject != null && !isDeviceCredentialAllowed) {
                prompt.authenticate(promptInfo, cryptoObject)
            } else {
                prompt.authenticate(promptInfo)
            }

            continuation.invokeOnCancellation {
                activity.runOnUiThread {
                    try {
                        prompt.cancelAuthentication()
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
