package project.kosh.core.auth.fido

/**
 * Thrown when the user explicitly cancels or dismisses the biometric authorization prompt.
 *
 * This exception signals to the authentication coordinator that the user chose to abort
 * the operation rather than a cryptographic or system failure occurring.
 *
 * @param message Human-readable cancellation description.
 */
class BiometricCancelledException(
    message: String = "Biometric prompt was cancelled by the user"
) : Exception(message)

/**
 * Thrown when a FIDO2 / WebAuthn cryptographic ceremony or transport validation fails.
 *
 * Encapsulates KeyStore errors, mathematical verification mismatches, or missing credential records.
 *
 * @param message Human-readable error description.
 * @param cause The originating throwable, if available.
 */
class FidoAuthenticationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
