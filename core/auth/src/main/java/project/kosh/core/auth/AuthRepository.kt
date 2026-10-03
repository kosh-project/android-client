package project.kosh.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import project.kosh.core.crypto.HashcashSolver
import project.kosh.core.model.AuthCredential
import project.kosh.core.model.AuthRequest
import project.kosh.core.model.NetworkResult
import project.kosh.core.network.AuthClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain repository orchestrating user authentication, registration, and session lifecycle.
 *
 * Coordinates between low-level network clients ([AuthClient]), cryptographic challenge solvers
 * ([HashcashSolver]), and hardware-backed session storage ([SessionManager]).
 *
 * ### Multi-Step Authentication Pipeline
 * Because the backend server enforces stateless Hashcash rate limiting on authentication routes,
 * authentication is executed as a multi-step protocol:
 * 1. **Challenge Acquisition:** The repository fetches a dynamic 15-second challenge from the server.
 * 2. **Proof-of-Work Solving:** [HashcashSolver] finds a 64-bit nonce satisfying 16 leading zero bits
 *    over the user's identity hash and challenge string.
 * 3. **Wire Request Transmission:** The repository submits the solved `X-Hashcash` header alongside the
 *    Argon2id `auth_verifier` within an [AuthRequest] payload on [Dispatchers.IO].
 * 4. **Session Persistence:** On login success, the issued 118-byte bearer token is saved to secure storage.
 *
 * @param authClient Network client communicating with public auth endpoints.
 * @param sessionManager Keystore-backed persistence for active bearer tokens.
 * @param hashcashSolver Proof-of-Work puzzle solver.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val authClient: AuthClient,
    private val sessionManager: SessionManager,
    private val hashcashSolver: HashcashSolver
) {

    /**
     * Executes the registration pipeline for a new user account.
     *
     * Fetches a `"register"` challenge, computes the 168-byte `X-Hashcash` header, and submits
     * the registration request to the server.
     *
     * @param credential The derived credentials containing the identity hash and Argon2id verifier.
     * @return [NetworkResult.Success] on completion, or [NetworkResult.Failure] describing the error
     *   (such as [NetworkResult.Http.Conflict] if the identity is already registered).
     */
    suspend fun register(credential: AuthCredential): NetworkResult<Unit> =
        withContext(Dispatchers.IO) {
            val challenge = when (val challengeResult = authClient.fetchChallenge("register")) {
                is NetworkResult.Success -> challengeResult.data.challenge
                is NetworkResult.Failure -> return@withContext challengeResult
            }

            val hashcashHeader = hashcashSolver.solve(credential.identityHash, challenge)

            val requestDto = AuthRequest(authVerifier = credential.authVerifier)
            authClient.register(requestDto, hashcashHeader)
        }

    /**
     * Executes the login pipeline for an existing user account.
     *
     * Fetches a `"login"` challenge, computes the 165-byte `X-Hashcash` header, and transmits
     * credentials to `/api/auth/login`. On success, persists the returned bearer token in [SessionManager].
     *
     * @param credential The derived credentials containing the identity hash and Argon2id verifier.
     * @return [NetworkResult.Success] carrying the issued 118-byte bearer token string,
     *   or [NetworkResult.Failure] if authentication fails.
     */
    suspend fun login(credential: AuthCredential): NetworkResult<String> =
        withContext(Dispatchers.IO) {
            val challengeResult = authClient.fetchChallenge("login")
            val challenge = when (challengeResult) {
                is NetworkResult.Success -> challengeResult.data.challenge
                is NetworkResult.Failure -> return@withContext challengeResult
            }

            val hashcashHeader = hashcashSolver.solve(credential.identityHash, challenge)

            val requestDto = AuthRequest(authVerifier = credential.authVerifier)
            val loginResult = authClient.login(requestDto, hashcashHeader)

            if (loginResult is NetworkResult.Success) {
                sessionManager.saveToken(loginResult.data)
            }

            loginResult
        }

    /**
     * Clears active authentication tokens from hardware-encrypted storage.
     */
    fun logout() {
        sessionManager.clearToken()
    }

    /**
     * Determines whether an active session token is currently persisted on the device.
     *
     * @return `true` if a non-null token exists in secure storage; `false` otherwise.
     */
    fun isLoggedIn(): Boolean {
        return sessionManager.getToken() != null
    }
}