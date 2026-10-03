package project.kosh.core.network

/**
 * Architectural abstraction providing access to the active authentication session token.
 *
 * This interface decouples the `:core:network` module from the concrete session storage
 * implementation located in `:core:auth` ([project.kosh.core.auth.SessionManager]), preventing
 * circular module dependencies while allowing [AuthInterceptor] to inject bearer tokens.
 */
interface TokenProvider {
    /**
     * Retrieves the active bearer token if a session is currently established.
     *
     * @return The 118-byte composite bearer token string (`<uuid>.<expires_hex>.<blake3_mac>`),
     *   or `null` if the user is unauthenticated or the session has been cleared.
     */
    fun getToken(): String?
}