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
            val promptBuilder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText("Cancel")

            if (cryptoObject != null) {
                promptBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            } else {
                promptBuilder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
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
                            continuation.resumeWithException(
                                FidoAuthenticationException("Biometric prompt error ($errorCode): $errString")
                            )
                        }
                    }
                }

                override fun onAuthenticationFailed() {
                    // Biometric attempt rejected (e.g. unrecognized fingerprint).
                    // The prompt dialog remains visible on screen allowing subsequent attempts.
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)
            if (cryptoObject != null) {
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
