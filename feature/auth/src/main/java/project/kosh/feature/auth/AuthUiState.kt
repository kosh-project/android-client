package project.kosh.feature.auth

/**
 * Sealed algebraic data type representing the discrete UI states of the authentication screen.
 *
 * Implements Unidirectional Data Flow (UDF) state modeling, consumed by [AuthScreen] via
 * a reactive [kotlinx.coroutines.flow.StateFlow].
 */
sealed class AuthUiState {
    /**
     * Initial resting state awaiting user credentials input.
     */
    data object Idle : AuthUiState()

    /**
     * Active state indicating background operations such as credential derivation (Argon2id),
     * Proof-of-Work puzzle solving, passkey verification, or HTTP authentication.
     *
     * @property message Contextual operation description for user feedback.
     */
    data class Loading(val message: String = "Processing...") : AuthUiState()

    /**
     * Terminal success state indicating that login or registration completed successfully.
     *
     * @property isNewRegistration True if account was freshly registered; routes to post-registration setup.
     * @property recoveryWords Optional 24-word BIP-39 recovery mnemonic generated during registration.
     */
    data class Success(
        val isNewRegistration: Boolean = false,
        val recoveryWords: List<String>? = null
    ) : AuthUiState()

    /**
     * State emitted when passkey assertion succeeded on a new device where the local
     * KeyStore does not yet contain the unwrapped Master Vault Key.
     *
     * @property token Active bearer token string.
     * @property wrappedVaultKey 120-character hex string representing the wrapped vault key envelope.
     */
    data class VaultUnlockRequired(
        val token: String,
        val wrappedVaultKey: String,
        val errorMessage: String? = null,
        val isUnlocking: Boolean = false
    ) : AuthUiState()

    /**
     * Error state displaying a human-readable failure notification.
     *
     * @property message The user-facing error message describing why authentication failed.
     */
    data class Error(val message: String) : AuthUiState()
}