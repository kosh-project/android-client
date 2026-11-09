package project.kosh.core.auth

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import project.kosh.core.auth.fido.BiometricCancelledException
import project.kosh.core.auth.fido.InternalFidoAuthenticator
import project.kosh.core.crypto.AesGcmEnvelope
import project.kosh.core.crypto.IdentityManager
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PasskeyLoginFinishRequest
import project.kosh.core.model.PasskeyMetadataRow
import project.kosh.core.model.PasskeyRegisterFinishRequest
import project.kosh.core.network.PasskeyClient
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result state emitted during passkey registration ceremony.
 */
sealed interface PasskeyRegisterResult {
    /**
     * Passkey creation, authenticator attestation, and server registration succeeded.
     */
    data object Success : PasskeyRegisterResult

    /**
     * User explicitly cancelled the biometric registration bottom sheet.
     */
    data object Cancelled : PasskeyRegisterResult

    /**
     * Registration failed due to network, transport, server, or biometric authentication errors.
     *
     * @property message Human-readable error description.
     * @property cause The originating throwable, if available.
     */
    data class Error(val message: String, val cause: Throwable? = null) : PasskeyRegisterResult
}

/**
 * Result state emitted during discoverable passkey login ceremony.
 */
sealed interface PasskeyLoginResult {
    /**
     * Passkey assertion verified and new authenticated session token issued.
     *
     * @property token The 118-byte bearer session token issued by the server.
     * @property wrappedVaultKey The 120-hex-character wrapped master vault key.
     * @property masterVaultKey The decrypted [MasterVaultKey] if unlocked via passphrase or already cached in storage.
     */
    data class Success(
        val token: String,
        val wrappedVaultKey: String,
        val masterVaultKey: MasterVaultKey? = null
    ) : PasskeyLoginResult

    /**
     * User explicitly cancelled the biometric sign-in dialog.
     */
    data object Cancelled : PasskeyLoginResult

    /**
     * Login ceremony failed due to network, verification, or credential provider errors.
     *
     * @property message Human-readable error description.
     * @property cause The originating throwable, if available.
     */
    data class Error(val message: String, val cause: Throwable? = null) : PasskeyLoginResult
}

/**
 * Domain-level coordinator managing FIDO2 / WebAuthn passkey operations.
 *
 * Coordinates interactions between:
 * 1. [PasskeyClient]: Network communication with Kosh Axum server endpoints.
 * 2. [InternalFidoAuthenticator]: Native hardware-enclave FIDO2 authenticator providing WebAuthn operations.
 * 3. [SessionManager]: Hardware-backed keystore persistence for bearer tokens and root vault credentials.
 * 4. [IdentityManager]: Argon2id key derivation and vault envelope unwrapping.
 *
 * Provides zero-knowledge device nickname encryption under the user's [MasterVaultKey] before
 * metadata is submitted to the backend.
 */
@Singleton
class PasskeyAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val passkeyClient: PasskeyClient,
    private val sessionManager: SessionManager,
    private val identityManager: IdentityManager,
    private val internalAuthenticator: InternalFidoAuthenticator
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Checks whether this device has any enrolled FIDO2 passkeys stored in the hardware authenticator.
     */
    fun hasEnrolledPasskeys(): Boolean {
        return internalAuthenticator.getAllCredentials().isNotEmpty()
    }

    /**
     * Clears all locally held passkey credentials and enclave keys.
     */
    fun clearPasskeys() {
        internalAuthenticator.clearAllCredentials()
    }

    /**
     * Enrolls a new biometric passkey for the currently authenticated account.
     *
     * 1. Confirms presence of an active session token in [SessionManager].
     * 2. Initiates passkey registration ceremony with the server via [PasskeyClient.startRegistration].
     * 3. Zero-Knowledge encrypts the device nickname using [MasterVaultKey] if available.
     * 4. Executes the registration ceremony via [InternalFidoAuthenticator.registerCredential].
     * 5. Submits the verified authenticator attestation response to `/api/v1/auth/passkey/register/finish`.
     *
     * @param activityContext Android Activity context required to render the biometric dialog.
     * @param deviceNickname Optional user-defined device label (defaults to manufacturer and model).
     * @return [PasskeyRegisterResult.Success], [PasskeyRegisterResult.Cancelled], or [PasskeyRegisterResult.Error].
     */
    suspend fun registerPasskey(
        activityContext: Context,
        deviceNickname: String? = null
    ): PasskeyRegisterResult = withContext(Dispatchers.IO) {
        val token = sessionManager.getToken()
            ?: return@withContext PasskeyRegisterResult.Error("Active session token required for passkey registration")

        val startResult = passkeyClient.startRegistration(token)
        val challengeResponse = when (startResult) {
            is NetworkResult.Success -> startResult.data
            is NetworkResult.Failure -> return@withContext PasskeyRegisterResult.Error(startResult.userMessage, startResult.cause)
        }

        try {
            val root = json.parseToJsonElement(challengeResponse.publicKeyJson)
            if (root !is JsonObject) {
                return@withContext PasskeyRegisterResult.Error("Invalid registration challenge: Expected JSON object")
            }
        } catch (e: Exception) {
            return@withContext PasskeyRegisterResult.Error("Invalid registration challenge: ${e.message}", e)
        }

        val masterKey = sessionManager.getVaultKey()
        val nickname = (deviceNickname?.trim()?.ifEmpty { null } ?: getDefaultDeviceName()).take(200)
        val encryptedMetadata = if (masterKey != null) {
            try {
                encryptMetadata(nickname, masterKey)
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }

        val credentialElement = try {
            internalAuthenticator.registerCredential(activityContext, challengeResponse)
        } catch (_: BiometricCancelledException) {
            return@withContext PasskeyRegisterResult.Cancelled
        } catch (e: Exception) {
            val msg = if (e.message?.contains("challenge", ignoreCase = true) == true) {
                "Invalid registration challenge: ${e.message}"
            } else {
                e.message ?: "Passkey registration failed"
            }
            return@withContext PasskeyRegisterResult.Error(msg, e)
        }

        val finishRequest = PasskeyRegisterFinishRequest(
            challengeId = challengeResponse.challengeId,
            credential = credentialElement,
            encryptedMetadata = encryptedMetadata
        )

        val finishResult = passkeyClient.finishRegistration(finishRequest, token)
        when (finishResult) {
            is NetworkResult.Success -> PasskeyRegisterResult.Success
            is NetworkResult.Failure -> PasskeyRegisterResult.Error(finishResult.userMessage, finishResult.cause)
        }
    }

    /**
     * Enrolls a new biometric passkey using an ephemeral single-use registration ticket minted during device pairing.
     *
     * Enables a newly paired device to enroll hardware-backed biometric passkeys without requiring passwords or pre-existing session tokens.
     *
     * 1. Fetches registration challenge from `/api/auth/passkey/register/start` using [registrationTicket].
     * 2. Prompts user biometric verification via [InternalFidoAuthenticator.registerCredential].
     * 3. Zero-Knowledge encrypts device nickname under [MasterVaultKey] stored in [SessionManager].
     * 4. Submits authenticator attestation to `/api/auth/passkey/register/finish`, consuming the registration ticket.
     *
     * @param activityContext Android Activity context required to render the biometric dialog.
     * @param registrationTicket 64-character hexadecimal single-use registration ticket.
     * @param deviceNickname Optional user-defined device label.
     * @return [PasskeyRegisterResult.Success], [PasskeyRegisterResult.Cancelled], or [PasskeyRegisterResult.Error].
     */
    suspend fun registerPasskeyWithTicket(
        activityContext: Context,
        registrationTicket: String,
        deviceNickname: String? = null
    ): PasskeyRegisterResult = withContext(Dispatchers.IO) {
        val startResult = passkeyClient.startRegistrationWithTicket(registrationTicket)
        val challengeResponse = when (startResult) {
            is NetworkResult.Success -> startResult.data
            is NetworkResult.Failure -> return@withContext PasskeyRegisterResult.Error(startResult.userMessage, startResult.cause)
        }

        try {
            val root = json.parseToJsonElement(challengeResponse.publicKeyJson)
            if (root !is JsonObject) {
                return@withContext PasskeyRegisterResult.Error("Invalid registration challenge: Expected JSON object")
            }
        } catch (e: Exception) {
            return@withContext PasskeyRegisterResult.Error("Invalid registration challenge: ${e.message}", e)
        }

        val masterKey = sessionManager.getVaultKey()
        val nickname = (deviceNickname?.trim()?.ifEmpty { null } ?: getDefaultDeviceName()).take(200)
        val encryptedMetadata = if (masterKey != null) {
            try {
                encryptMetadata(nickname, masterKey)
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }

        val credentialElement = try {
            internalAuthenticator.registerCredential(activityContext, challengeResponse)
        } catch (_: BiometricCancelledException) {
            return@withContext PasskeyRegisterResult.Cancelled
        } catch (e: Exception) {
            val msg = if (e.message?.contains("challenge", ignoreCase = true) == true) {
                "Invalid registration challenge: ${e.message}"
            } else {
                e.message ?: "Passkey registration failed"
            }
            return@withContext PasskeyRegisterResult.Error(msg, e)
        }

        val finishRequest = PasskeyRegisterFinishRequest(
            challengeId = challengeResponse.challengeId,
            credential = credentialElement,
            encryptedMetadata = encryptedMetadata,
            ticket = registrationTicket
        )

        val finishResult = passkeyClient.finishRegistrationWithTicket(finishRequest, registrationTicket)
        when (finishResult) {
            is NetworkResult.Success -> PasskeyRegisterResult.Success
            is NetworkResult.Failure -> PasskeyRegisterResult.Error(finishResult.userMessage, finishResult.cause)
        }
    }

    /**
     * Executes discoverable (resident key / passwordless) passkey authentication.
     *
     * 1. Fetches an ephemeral discoverable challenge from `/api/auth/passkey/login/start`.
     * 2. Prompts user biometric verification via [InternalFidoAuthenticator.authenticateCredential].
     * 3. Submits authenticator assertion to `/api/auth/passkey/login/finish`.
     * 4. Persists the returned 118-byte bearer session token in [SessionManager].
     * 5. If [passphrase] is supplied, unwraps and stores the 32-byte [MasterVaultKey].
     *
     * @param activityContext Android Activity context required to render the biometric selection sheet.
     * @param passphrase Optional master passphrase to decrypt and unlock the vault key immediately.
     * @return [PasskeyLoginResult.Success], [PasskeyLoginResult.Cancelled], or [PasskeyLoginResult.Error].
     */
    suspend fun loginWithPasskey(
        activityContext: Context,
        passphrase: String? = null
    ): PasskeyLoginResult = withContext(Dispatchers.IO) {
        val startResult = passkeyClient.startLogin()
        val challengeResponse = when (startResult) {
            is NetworkResult.Success -> startResult.data
            is NetworkResult.Failure -> return@withContext PasskeyLoginResult.Error(startResult.userMessage, startResult.cause)
        }

        try {
            val root = json.parseToJsonElement(challengeResponse.publicKeyJson)
            if (root !is JsonObject) {
                return@withContext PasskeyLoginResult.Error("Invalid login challenge: Expected JSON object")
            }
        } catch (e: Exception) {
            return@withContext PasskeyLoginResult.Error("Invalid login challenge: ${e.message}", e)
        }

        val credentialElement = try {
            internalAuthenticator.authenticateCredential(activityContext, challengeResponse)
        } catch (_: BiometricCancelledException) {
            return@withContext PasskeyLoginResult.Cancelled
        } catch (e: Exception) {
            val msg = if (e.message?.contains("challenge", ignoreCase = true) == true) {
                "Invalid login challenge: ${e.message}"
            } else {
                e.message ?: "Biometric passkey sign-in failed"
            }
            return@withContext PasskeyLoginResult.Error(msg, e)
        }

        val finishRequest = PasskeyLoginFinishRequest(
            challengeId = challengeResponse.challengeId,
            credential = credentialElement
        )

        val finishResult = passkeyClient.finishLogin(finishRequest)
        when (finishResult) {
            is NetworkResult.Success -> {
                val authResponse = finishResult.data
                sessionManager.saveToken(authResponse.token)

                var unlockedMasterKey: MasterVaultKey? = null
                if (!passphrase.isNullOrBlank()) {
                    try {
                        val kek = identityManager.deriveKek(passphrase)
                        val masterKey = AesGcmEnvelope.unwrap(authResponse.wrappedVaultKey, kek)
                        sessionManager.saveVaultKey(masterKey)
                        unlockedMasterKey = masterKey
                    } catch (_: Exception) {
                        // Vault remains locked until explicit unlock ceremony
                    }
                } else {
                    unlockedMasterKey = sessionManager.getVaultKey()
                }

                PasskeyLoginResult.Success(
                    token = authResponse.token,
                    wrappedVaultKey = authResponse.wrappedVaultKey,
                    masterVaultKey = unlockedMasterKey
                )
            }
            is NetworkResult.Failure -> PasskeyLoginResult.Error(finishResult.userMessage, finishResult.cause)
        }
    }

    /**
     * Unwraps and securely persists the [MasterVaultKey] using [passphrase].
     *
     * @param wrappedVaultKeyHex 120-character hex string returned by login.
     * @param passphrase Secret user passphrase.
     * @return Decrypted [MasterVaultKey], or null if verification failed.
     */
    fun unlockVaultWithPassphrase(wrappedVaultKeyHex: String, passphrase: String): MasterVaultKey? {
        val cleanKeyHex = wrappedVaultKeyHex.trim()
        if (passphrase.isBlank() || cleanKeyHex.isBlank()) return null
        val normalized = identityManager.normalizeRecoveryPhrase(passphrase)
        if (!normalized.isNullOrBlank()) {
            try {
                val kek = identityManager.deriveKek(normalized)
                val masterKey = AesGcmEnvelope.unwrap(cleanKeyHex, kek)
                sessionManager.saveVaultKey(masterKey)
                return masterKey
            } catch (_: Exception) {
                // Fall through to unnormalized passphrase if distinct
            }
        }
        val cleanPassphrase = passphrase.trim()
        return try {
            val kek = identityManager.deriveKek(cleanPassphrase)
            val masterKey = AesGcmEnvelope.unwrap(cleanKeyHex, kek)
            sessionManager.saveVaultKey(masterKey)
            masterKey
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Lists all passkey credentials enrolled for the caller, sorted newest to oldest.
     *
     * @param token Optional explicit bearer token.
     */
    suspend fun listPasskeys(token: String? = null): NetworkResult<List<PasskeyMetadataRow>> {
        return passkeyClient.listPasskeys(token)
    }

    /**
     * Permanently revokes and removes a passkey credential from the server.
     *
     * @param credentialId Hex-encoded credential identifier.
     * @param token Optional explicit bearer token.
     */
    suspend fun deletePasskey(credentialId: String, token: String? = null): NetworkResult<Unit> {
        internalAuthenticator.deleteCredential(credentialId)
        return passkeyClient.deletePasskey(credentialId, token)
    }

    /**
     * Encrypts a device label or nickname into a hex-encoded AES-256-GCM envelope using [MasterVaultKey].
     *
     * Wire layout (up to 2048 hex characters):
     * ```text
     * [12 bytes IV][Plaintext length bytes Ciphertext][16 bytes GCM Tag]
     * ```
     *
     * @param metadata Plaintext string label to protect.
     * @param masterKey 32-byte Master Vault Key.
     * @param random Cryptographically secure RNG.
     * @return Hex-encoded ciphertext string.
     */
    fun encryptMetadata(
        metadata: String,
        masterKey: MasterVaultKey,
        random: SecureRandom = SecureRandom()
    ): String {
        if (metadata.isEmpty()) return ""

        val iv = ByteArray(12)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(masterKey.keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val plaintextBytes = metadata.toByteArray(Charsets.UTF_8)
        val ciphertextAndTag = cipher.doFinal(plaintextBytes)

        val combined = ByteArray(iv.size + ciphertextAndTag.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertextAndTag, 0, combined, iv.size, ciphertextAndTag.size)

        val hex = bytesToHex(combined)
        require(hex.length <= 2048) { "Encrypted metadata exceeds maximum 2048 hex characters" }
        return hex
    }

    /**
     * Decrypts a hex-encoded device label previously encrypted with [encryptMetadata].
     *
     * @param encryptedHex Hex-encoded ciphertext string.
     * @param masterKey 32-byte Master Vault Key.
     * @return Decrypted plaintext string, or null if decryption / MAC verification fails.
     */
    fun decryptMetadata(
        encryptedHex: String,
        masterKey: MasterVaultKey
    ): String? {
        if (encryptedHex.isEmpty()) return ""
        if (encryptedHex.length % 2 != 0 || encryptedHex.length < 56 || encryptedHex.length > 2048) return null

        return try {
            val bytes = hexToBytes(encryptedHex) ?: return null
            val iv = bytes.copyOfRange(0, 12)
            val ciphertextAndTag = bytes.copyOfRange(12, bytes.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(masterKey.keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            val decrypted = cipher.doFinal(ciphertextAndTag)
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves a human-readable display label for a passkey summary record.
     *
     * Attempts decryption using [masterKey]. If decryption succeeds, returns the plaintext
     * nickname; otherwise returns a fallback label based on the credential ID.
     */
    fun decryptNickname(
        row: PasskeyMetadataRow,
        masterKey: MasterVaultKey? = sessionManager.getVaultKey()
    ): String {
        if (masterKey != null && row.encryptedMetadata.isNotEmpty()) {
            val decrypted = decryptMetadata(row.encryptedMetadata, masterKey)
            if (!decrypted.isNullOrBlank()) {
                return decrypted
            }
        }
        val cleanId = row.credentialId.trim()
        val suffix = if (cleanId.length >= 8) cleanId.take(8) else cleanId
        return if (suffix.isNotEmpty()) "Passkey ($suffix)" else "Passkey"
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

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray? {
        val cleanHex = hex.trim()
        if (cleanHex.length % 2 != 0) return null
        val len = cleanHex.length
        val result = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            val d1 = Character.digit(cleanHex[i], 16)
            val d2 = Character.digit(cleanHex[i + 1], 16)
            if (d1 == -1 || d2 == -1) return null
            result[i / 2] = ((d1 shl 4) or d2).toByte()
        }
        return result
    }
}
