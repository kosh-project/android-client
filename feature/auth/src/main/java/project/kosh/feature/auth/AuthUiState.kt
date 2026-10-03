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
    object Idle : AuthUiState()

    /**
     * Active state indicating that credential derivation (Argon2id), Proof-of-Work puzzle solving,
     * or HTTP authentication requests are executing in the background.
     */
    object Loading : AuthUiState()

    /**
     * Terminal success state indicating that login or registration completed successfully.
     * Triggers navigation transitions from authentication to the application main flow.
     */
    object Success : AuthUiState()

    /**
     * Error state displaying a human-readable failure notification.
     *
     * @property message The user-facing error message describing why authentication failed.
     */
    data class Error(val message: String) : AuthUiState()
}