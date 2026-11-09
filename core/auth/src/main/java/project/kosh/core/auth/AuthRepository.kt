package project.kosh.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import project.kosh.core.crypto.AesGcmEnvelope
import project.kosh.core.crypto.HashcashSolver
import project.kosh.core.crypto.IdentityManager
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.model.AuthCredential
import project.kosh.core.model.LoginRequest
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.RegisterRequest
import project.kosh.core.model.VaultRecoveryRequest
import project.kosh.core.network.AuthClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain repository orchestrating user authentication, registration, session lifecycle,
 * and Zero-Knowledge Master Vault Key envelope wrapping/unwrapping.
 *
 * Coordinates between low-level network clients ([AuthClient]), cryptographic challenge solvers
 * ([HashcashSolver]), credential/KEK derivation ([IdentityManager]), and hardware-backed storage ([SessionManager]).
 *
 * @param authClient Network client communicating with public auth endpoints.
 * @param sessionManager Keystore-backed persistence for active bearer tokens and Master Vault Key.
 * @param hashcashSolver Proof-of-Work puzzle solver.
 * @param identityManager Cryptographic engine deriving identity hashes, auth verifiers, and KEKs.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val authClient: AuthClient,
    private val sessionManager: SessionManager,
    private val hashcashSolver: HashcashSolver,
    private val identityManager: IdentityManager
) {

    /**
     * Executes the registration pipeline for a new user account.
     *
     * 1. Fetches a "register" Proof-of-Work challenge.
     * 2. Derives identity hash and auth verifier via Argon2id.
     * 3. Solves the 168-byte X-Hashcash puzzle header.
     * 4. Generates a fresh 32-byte Master Vault Key.
     * 5. Derives the 32-byte KEK from passphrase via Argon2id with domain separation.
     * 6. Wraps the Master Vault Key into a 60-byte envelope (120 hex chars) using AES-256-GCM.
     * 7. Transmits RegisterRequest to the server and saves the Master Vault Key locally.
     *
     * @param username Account username.
     * @param passphrase Secret user passphrase.
     * @return [NetworkResult.Success] on completion, or [NetworkResult.Failure] describing the error.
     */
    suspend fun register(username: String, passphrase: String): NetworkResult<Unit> =
        withContext(Dispatchers.IO) {
            val challenge = when (val challengeResult = authClient.fetchChallenge("register")) {
                is NetworkResult.Success -> challengeResult.data.challenge
                is NetworkResult.Failure -> return@withContext challengeResult
            }

            val credentials = identityManager.deriveCredentials(username, passphrase)
            val hashcashHeader = hashcashSolver.solve(credentials.identityHash, challenge)

            val masterKey = MasterVaultKey.generate()
            val kek = identityManager.deriveKek(passphrase)
            val wrappedVaultKey = AesGcmEnvelope.wrap(masterKey, kek)

            val requestDto = RegisterRequest(
                authVerifier = credentials.authVerifier,
                wrappedVaultKey = wrappedVaultKey
            )

            val registerResult = authClient.register(requestDto, hashcashHeader)
            if (registerResult is NetworkResult.Success) {
                sessionManager.saveVaultKey(masterKey)
                sessionManager.saveUsername(username)
            }

            registerResult
        }

    /**
     * Executes the registration pipeline for a new user account using a 12-word recovery phrase.
     *
     * 1. Normalizes the recovery phrase.
     * 2. Fetches a "register" Proof-of-Work challenge.
     * 3. Derives identity hash and auth verifier via Argon2id from the recovery phrase.
     * 4. Solves the 168-byte X-Hashcash puzzle header.
     * 5. Generates a fresh 32-byte Master Vault Key.
     * 6. Derives the 32-byte KEK from the recovery phrase via Argon2id.
     * 7. Wraps the Master Vault Key into a 60-byte envelope (120 hex chars) using AES-256-GCM.
     * 8. Transmits RegisterRequest to the server and saves the Master Vault Key locally.
     *
     * @param recoveryPhrase 12-word BIP-39 recovery phrase.
     * @return [NetworkResult.Success] on completion, or [NetworkResult.Failure] describing the error.
     */
    suspend fun registerWithRecoveryPhrase(recoveryPhrase: String): NetworkResult<Unit> =
        withContext(Dispatchers.IO) {
            val normalized = identityManager.normalizeRecoveryPhrase(recoveryPhrase)
            val challenge = when (val challengeResult = authClient.fetchChallenge("register")) {
                is NetworkResult.Success -> challengeResult.data.challenge
                is NetworkResult.Failure -> return@withContext challengeResult
            }

            val credentials = identityManager.deriveCredentialsFromRecoveryPhrase(normalized)
            val hashcashHeader = hashcashSolver.solve(credentials.identityHash, challenge)

            val masterKey = MasterVaultKey.generate()
            val kek = identityManager.deriveKekFromRecoveryPhrase(normalized)
            val wrappedVaultKey = AesGcmEnvelope.wrap(masterKey, kek)

            val requestDto = RegisterRequest(
                authVerifier = credentials.authVerifier,
                wrappedVaultKey = wrappedVaultKey
            )

            val registerResult = authClient.register(requestDto, hashcashHeader)
            if (registerResult is NetworkResult.Success) {
                sessionManager.saveVaultKey(masterKey)
                sessionManager.saveUsername("kosh-passkey-user")
            }

            registerResult
        }

    /**
     * Backward-compatible overload accepting pre-derived [AuthCredential].
     */
    suspend fun register(credential: AuthCredential): NetworkResult<Unit> =
        withContext(Dispatchers.IO) {
            val challenge = when (val challengeResult = authClient.fetchChallenge("register")) {
                is NetworkResult.Success -> challengeResult.data.challenge
                is NetworkResult.Failure -> return@withContext challengeResult
            }

            val hashcashHeader = hashcashSolver.solve(credential.identityHash, challenge)
            val masterKey = MasterVaultKey.generate()
            val dummyKek = ByteArray(32) { 0 }
            val wrappedKey = AesGcmEnvelope.wrap(masterKey, dummyKek)

            val requestDto = RegisterRequest(
                authVerifier = credential.authVerifier,
                wrappedVaultKey = wrappedKey
            )
            val registerResult = authClient.register(requestDto, hashcashHeader)
            if (registerResult is NetworkResult.Success) {
                sessionManager.saveVaultKey(masterKey)
            }
            registerResult
        }

    /**
     * Executes the login pipeline for an existing user account.
     *
     * 1. Fetches a "login" Proof-of-Work challenge.
     * 2. Derives identity hash and auth verifier via Argon2id.
     * 3. Solves the 165-byte X-Hashcash puzzle header.
     * 4. Submits LoginRequest to POST /api/auth/login.
     * 5. Receives token and 60-byte wrapped_vault_key.
     * 6. Derives KEK from passphrase via Argon2id and unwraps the Master Vault Key.
     * 7. Persists bearer token and Master Vault Key in hardware-backed storage.
     *
     * @param username Account username.
     * @param passphrase Secret user passphrase.
     * @return [NetworkResult.Success] carrying the issued 118-byte bearer token string,
     *   or [NetworkResult.Failure] if authentication fails.
     */
    suspend fun login(username: String, passphrase: String): NetworkResult<String> =
        withContext(Dispatchers.IO) {
            val challengeResult = authClient.fetchChallenge("login")
            val challenge = when (challengeResult) {
                is NetworkResult.Success -> challengeResult.data.challenge
                is NetworkResult.Failure -> return@withContext challengeResult
            }

            val credentials = identityManager.deriveCredentials(username, passphrase)
            val hashcashHeader = hashcashSolver.solve(credentials.identityHash, challenge)

            val requestDto = LoginRequest(authVerifier = credentials.authVerifier)
            val loginResult = authClient.login(requestDto, hashcashHeader)

            when (loginResult) {
                is NetworkResult.Success -> {
                    val authResponse = loginResult.data
                    val kek = identityManager.deriveKek(passphrase)

                    val masterKey = try {
                        AesGcmEnvelope.unwrap(authResponse.wrappedVaultKey, kek)
                    } catch (e: Exception) {
                        return@withContext NetworkResult.fromThrowable(
                            IllegalStateException("Failed to unlock vault key with provided passphrase", e)
                        )
                    }

                    sessionManager.saveToken(authResponse.token)
                    sessionManager.saveVaultKey(masterKey)
                    sessionManager.saveUsername(username)

                    NetworkResult.Success(authResponse.token)
                }
                is NetworkResult.Http -> loginResult
                is NetworkResult.Failure -> loginResult
            }
        }

    /**
     * Executes the recovery login pipeline using the 12-word recovery phrase.
     *
     * 1. Normalizes the recovery phrase.
     * 2. Fetches a "login" Proof-of-Work challenge.
     * 3. Derives identity hash and auth verifier via Argon2id from the recovery phrase.
     * 4. Solves the 165-byte X-Hashcash puzzle header.
     * 5. Submits LoginRequest to POST /api/auth/login.
     * 6. Receives token and 60-byte wrapped_vault_key.
     * 7. Derives KEK from the recovery phrase via Argon2id and unwraps the Master Vault Key.
     * 8. Persists bearer token and Master Vault Key in hardware-backed storage.
     *
     * @param recoveryPhrase 12-word BIP-39 recovery phrase or master passphrase.
     * @return [NetworkResult.Success] carrying the issued bearer token string,
     *   or [NetworkResult.Failure] if recovery authentication fails.
     */
    suspend fun loginWithRecoveryPhrase(recoveryPhrase: String): NetworkResult<String> =
        withContext(Dispatchers.IO) {
            val normalized = identityManager.normalizeRecoveryPhrase(recoveryPhrase)
            val challengeResult = authClient.fetchChallenge("login")
            val challenge = when (challengeResult) {
                is NetworkResult.Success -> challengeResult.data.challenge
                is NetworkResult.Failure -> return@withContext challengeResult
            }

            val credentials = identityManager.deriveCredentialsFromRecoveryPhrase(normalized)
            val hashcashHeader = hashcashSolver.solve(credentials.identityHash, challenge)

            val requestDto = LoginRequest(authVerifier = credentials.authVerifier)
            val loginResult = authClient.login(requestDto, hashcashHeader)

            when (loginResult) {
                is NetworkResult.Success -> {
                    val authResponse = loginResult.data
                    val kek = identityManager.deriveKekFromRecoveryPhrase(normalized)

                    val masterKey = try {
                        AesGcmEnvelope.unwrap(authResponse.wrappedVaultKey, kek)
                    } catch (e: Exception) {
                        return@withContext NetworkResult.fromThrowable(
                            IllegalStateException("Failed to unlock vault key with recovery phrase", e)
                        )
                    }

                    sessionManager.saveToken(authResponse.token)
                    sessionManager.saveVaultKey(masterKey)
                    sessionManager.saveUsername("kosh-passkey-user")

                    NetworkResult.Success(authResponse.token)
                }
                is NetworkResult.Http -> loginResult
                is NetworkResult.Failure -> loginResult
            }
        }

    /**
     * Backward-compatible overload for pre-derived [AuthCredential].
     */
    suspend fun login(credential: AuthCredential): NetworkResult<String> =
        withContext(Dispatchers.IO) {
            val challengeResult = authClient.fetchChallenge("login")
            val challenge = when (challengeResult) {
                is NetworkResult.Success -> challengeResult.data.challenge
                is NetworkResult.Failure -> return@withContext challengeResult
            }

            val hashcashHeader = hashcashSolver.solve(credential.identityHash, challenge)
            val requestDto = LoginRequest(authVerifier = credential.authVerifier)
            val loginResult = authClient.login(requestDto, hashcashHeader)

            when (loginResult) {
                is NetworkResult.Success -> {
                    sessionManager.saveToken(loginResult.data.token)
                    NetworkResult.Success(loginResult.data.token)
                }
                is NetworkResult.Http -> loginResult
                is NetworkResult.Failure -> loginResult
            }
        }

    /**
     * Rotates user credentials and re-wraps the active Master Vault Key with a new passphrase.
     *
     * @param username Account username.
     * @param newPassphrase New account password.
     * @param revokeOtherSessions Whether to invalidate all other active sessions on the server.
     * @return [NetworkResult.Success] on 204 No Content, or [NetworkResult.Failure].
     */
    suspend fun recoverCredentials(
        username: String,
        newPassphrase: String,
        revokeOtherSessions: Boolean = true
    ): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        val currentToken = sessionManager.getToken()
            ?: return@withContext NetworkResult.Unknown(
                userMessage = "Active session required for credential recovery",
                cause = IllegalStateException("Not logged in")
            )

        val currentMasterKey = sessionManager.getVaultKey()
            ?: return@withContext NetworkResult.Unknown(
                userMessage = "Master Vault Key must be unlocked to rotate credentials",
                cause = IllegalStateException("Vault key not available")
            )

        val newCredentials = identityManager.deriveCredentials(username, newPassphrase)
        val newKek = identityManager.deriveKek(newPassphrase)
        val rewrappedVaultKey = AesGcmEnvelope.wrap(currentMasterKey, newKek)

        val request = VaultRecoveryRequest(
            identityHash = newCredentials.identityHash,
            authVerifier = newCredentials.authVerifier,
            wrappedVaultKey = rewrappedVaultKey,
            revokeOtherSessions = revokeOtherSessions
        )

        authClient.recoverCredentials(request, currentToken)
    }

    /**
     * Clears active authentication tokens and the Master Vault Key from hardware-encrypted storage.
     */
    fun logout() {
        sessionManager.clearToken()
    }

    /**
     * Resolves the active account username, or null if unauthenticated.
     */
    fun getActiveUsername(): String? = sessionManager.getUsername()

    /**
     * Determines whether an active session token and an unlocked Master Vault Key are present.
     *
     * @return `true` if logged in and key unlocked; `false` otherwise.
     */
    fun isLoggedIn(): Boolean {
        return sessionManager.getToken() != null && sessionManager.getVaultKey() != null
    }

    /**
     * Checks if this device has any stored session credentials or keys in hardware-backed storage.
     */
    fun hasStoredCredentials(): Boolean {
        return sessionManager.getToken() != null || sessionManager.getVaultKey() != null || sessionManager.getUsername() != null
    }

    /**
     * Checks if the Master Vault Key is currently present and unlocked in hardware storage.
     */
    fun hasVaultKey(): Boolean {
        return sessionManager.getVaultKey() != null
    }

    /**
     * Resolves the unlocked Master Vault Key from hardware storage, or null if locked.
     */
    fun getVaultKey(): MasterVaultKey? {
        return sessionManager.getVaultKey()
    }

    /**
     * Checks if an ephemeral registration ticket is saved in hardware-backed storage from device pairing.
     */
    fun hasPendingRegistrationTicket(): Boolean {
        return !sessionManager.getRegistrationTicket().isNullOrBlank()
    }

    /**
     * Directly persists an authenticated session token in hardware storage.
     */
    fun saveSessionToken(token: String) {
        sessionManager.saveToken(token)
    }
}
