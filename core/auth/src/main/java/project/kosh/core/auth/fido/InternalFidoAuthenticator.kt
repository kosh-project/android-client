package project.kosh.core.auth.fido

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricPrompt
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import project.kosh.core.model.PasskeyChallengeResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted local metadata for a resident FIDO2 / WebAuthn passkey enrolled on this device.
 *
 * @property credentialId Unpadded Base64URL-encoded credential identifier.
 * @property keyAlias Deterministic alias identifying the key in [KeyStoreProvider].
 * @property userHandle Unpadded Base64URL-encoded Relying Party user handle (UUID bytes).
 * @property rpId Relying Party identifier domain (e.g. "kosh-server.local" or "localhost").
 * @property signCount Monotonically incrementing signature counter for clone detection.
 * @property createdAt Epoch timestamp in milliseconds when the passkey was enrolled.
 */
@Serializable
data class EnrolledPasskeyRecord(
    val credentialId: String,
    val keyAlias: String,
    val userHandle: String,
    val rpId: String,
    val signCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Native Internal FIDO2 / WebAuthn Authenticator embedded within the Kosh Android application.
 *
 * Coordinates hardware-backed cryptographic passkey ceremonies directly inside the device's
 * hardware enclave (`AndroidKeyStore`), completely bypassing Android OS-level Credential Manager
 * restrictions on `.local` mDNS domains and self-signed TLS endpoints.
 *
 * ### Registration Ceremony:
 * 1. Confirms user presence via [BiometricPromptProvider].
 * 2. Generates an EC P-256 (`secp256r1`) keypair inside the hardware enclave.
 * 3. Constructs standards-compliant W3C WebAuthn Level 2 `clientDataJSON` (type `"webauthn.create"`,
 *    origin `"android:apk-key-hash:<hash>"`).
 * 4. Assembles canonical CBOR `attestationObject` with `"none"` format via [CborEncoder].
 * 5. Returns a standard `RegisterPublicKeyCredential` JSON structure.
 *
 * ### Discoverable Assertion (Login) Ceremony:
 * 1. Identifies the matching resident credential record from secure local storage.
 * 2. Formulates `authenticatorData` (37 bytes: 32B `rpIdHash` + 1B `flags 0x05` + 4B `counter`).
 * 3. Prompts the user for biometric authorization with hardware [BiometricPrompt.CryptoObject].
 * 4. Computes ECDSA-SHA256 signature over `authData || sha256(clientDataJSON)`.
 * 5. Returns a standard `PublicKeyCredential` assertion JSON structure.
 *
 * @param context Android application context for package signature resolution and preferences.
 * @param keyStoreProvider Abstraction managing hardware-enclave key generation and storage.
 * @param biometricPromptProvider Abstraction presenting biometric prompt UI dialogs.
 */
@Singleton
class InternalFidoAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStoreProvider: KeyStoreProvider,
    private val biometricPromptProvider: BiometricPromptProvider
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences =
        context.getSharedPreferences("kosh_internal_fido_authenticator", Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    /**
     * Resolves the canonical `android:apk-key-hash:<BASE64URL>` origin for this application.
     *
     * Computes the SHA-256 digest of the application's signing certificate, encoded as an
     * unpadded Base64URL string. If dynamic resolution fails, falls back to the deterministic
     * shared debug keystore fingerprint.
     */
    val origin: String by lazy {
        resolveApkKeyHashOrigin()
    }

    /**
     * Resolves the WebAuthn clientDataJSON origin.
     * When rpId is "localhost", "127.0.0.1", mDNS (".local"), "kosh", or a private LAN IP,
     * aligns with the server's Relying Party configuration ("http://localhost:23342"),
     * preventing 400 WebauthnError::InvalidRPOrigin.
     */
    fun resolveOriginForRp(rpId: String): String {
        val lower = rpId.lowercase()
        return if (lower == "localhost" ||
            lower == "127.0.0.1" ||
            lower.endsWith(".local") ||
            lower.contains("kosh") ||
            lower.startsWith("192.168.") ||
            lower.startsWith("10.") ||
            lower.startsWith("172.")
        ) {
            "http://localhost:23342"
        } else {
            origin
        }
    }

    /**
     * Executes the WebAuthn passkey registration ceremony.
     *
     * @param activityContext Host Activity context required to render the biometric dialog.
     * @param challengeResponse Challenge parameters received from `POST /api/v1/auth/passkey/register/start`.
     * @return [JsonObject] matching the W3C `RegisterPublicKeyCredential` specification.
     * @throws BiometricCancelledException If the user dismisses the biometric dialog.
     * @throws FidoAuthenticationException If cryptographic generation or parameter extraction fails.
     */
    suspend fun registerCredential(
        activityContext: Context,
        challengeResponse: PasskeyChallengeResponse
    ): JsonElement {
        val activity = activityContext.findFragmentActivity()
            ?: throw FidoAuthenticationException("FragmentActivity context required for biometric enrollment")

        // 1. Parse creation challenge parameters
        val challengeRoot = parseChallengeJson(challengeResponse.publicKeyJson)
        val serverChallenge = challengeRoot.extractString("challenge")
            ?: throw FidoAuthenticationException("Registration challenge missing 'challenge' field")

        val rpObj = challengeRoot.extractObject("rp")
        val rpId = rpObj?.extractString("id") ?: "localhost"

        val userObj = challengeRoot.extractObject("user")
        val userHandle = userObj?.extractString("id")
            ?: throw FidoAuthenticationException("Registration challenge missing 'user.id' field")

        // 2. Prompt user biometrics to authorize passkey creation
        biometricPromptProvider.authenticate(
            activity = activity,
            title = "Enroll Biometric Passkey",
            subtitle = "Scan your fingerprint to enroll a hardware passkey for $rpId",
            cryptoObject = null
        )

        // 3. Generate a 32-byte cryptographic credential ID
        val credentialIdBytes = ByteArray(32)
        secureRandom.nextBytes(credentialIdBytes)
        val credentialIdBase64Url = credentialIdBytes.toBase64Url()

        // 4. Generate EC P-256 keypair in hardware KeyStore
        val keyAlias = "kosh_passkey_$credentialIdBase64Url"
        val ecPublicKey: ECPublicKey = try {
            keyStoreProvider.generateEcP256KeyPair(keyAlias, requireUserAuth = true)
        } catch (e: Exception) {
            throw FidoAuthenticationException("Failed to generate enclave passkey: ${e.message}", e)
        }

        // 5. Build clientDataJSON: {"type":"webauthn.create","challenge":"...","origin":"...","crossOrigin":false}
        val effectiveOrigin = resolveOriginForRp(rpId)
        val clientDataJsonString = buildJsonObject {
            put("type", "webauthn.create")
            put("challenge", serverChallenge)
            put("origin", effectiveOrigin)
            put("crossOrigin", false)
        }.toString()
        val clientDataBytes = clientDataJsonString.toByteArray(Charsets.UTF_8)
        val clientDataBase64Url = clientDataBytes.toBase64Url()

        // 6. Compute rpIdHash (SHA-256 of rpId)
        val rpIdHash = MessageDigest.getInstance("SHA-256")
            .digest(rpId.toByteArray(Charsets.UTF_8))

        // 7. Assemble canonical CBOR attestationObject ("none" self-attestation)
        val attestationObjectBytes = CborEncoder.encodeAttestationObject(
            rpIdHash = rpIdHash,
            credentialId = credentialIdBytes,
            ecPublicKey = ecPublicKey,
            signCount = 0
        )
        val attestationObjectBase64Url = attestationObjectBytes.toBase64Url()

        // 8. Securely persist credential metadata locally
        val record = EnrolledPasskeyRecord(
            credentialId = credentialIdBase64Url,
            keyAlias = keyAlias,
            userHandle = userHandle,
            rpId = rpId,
            signCount = 0
        )
        saveCredential(record)

        // 9. Return standard RegisterPublicKeyCredential wire payload
        return buildJsonObject {
            put("id", credentialIdBase64Url)
            put("rawId", credentialIdBase64Url)
            put("type", "public-key")
            put("response", buildJsonObject {
                put("attestationObject", attestationObjectBase64Url)
                put("clientDataJSON", clientDataBase64Url)
            })
        }
    }

    /**
     * Executes discoverable (resident key / passwordless) passkey assertion authentication.
     *
     * @param activityContext Host Activity context required to render the biometric dialog.
     * @param challengeResponse Challenge parameters received from `POST /api/auth/passkey/login/start`.
     * @return [JsonObject] matching the W3C `PublicKeyCredential` assertion specification.
     * @throws BiometricCancelledException If the user dismisses the biometric prompt.
     * @throws FidoAuthenticationException If credential lookup, signing, or verification fails.
     */
    suspend fun authenticateCredential(
        activityContext: Context,
        challengeResponse: PasskeyChallengeResponse
    ): JsonElement {
        val activity = activityContext.findFragmentActivity()
            ?: throw FidoAuthenticationException("FragmentActivity context required for biometric authentication")

        // 1. Parse assertion challenge parameters
        val challengeRoot = parseChallengeJson(challengeResponse.publicKeyJson)
        val serverChallenge = challengeRoot.extractString("challenge")
            ?: throw FidoAuthenticationException("Login challenge missing 'challenge' field")

        val rpId = challengeRoot.extractString("rpId")
            ?: challengeRoot.extractObject("rp")?.extractString("id")
            ?: "localhost"

        // 2. Identify matching resident credential
        val allowedCreds = challengeRoot.extractArray("allowCredentials")
        val record = findMatchingCredential(allowedCreds, rpId)
            ?: throw FidoAuthenticationException("No matching enrolled biometric passkey found on this device")

        // 3. Monotonically increment signature counter
        val counter = incrementAndGetCounter(record.credentialId)

        // 4. Construct authenticatorData (37 bytes: 32B rpIdHash + 1B flags 0x05 + 4B counter)
        val rpIdHash = MessageDigest.getInstance("SHA-256")
            .digest(record.rpId.toByteArray(Charsets.UTF_8))
        val authDataBytes = CborEncoder.encodeAuthData(
            rpIdHash = rpIdHash,
            flags = CborEncoder.FLAGS_ASSERTION_BIOMETRIC,
            signCount = counter
        )
        val authDataBase64Url = authDataBytes.toBase64Url()

        // 5. Construct clientDataJSON: {"type":"webauthn.get","challenge":"...","origin":"...","crossOrigin":false}
        val effectiveOrigin = resolveOriginForRp(rpId)
        val clientDataJsonString = buildJsonObject {
            put("type", "webauthn.get")
            put("challenge", serverChallenge)
            put("origin", effectiveOrigin)
            put("crossOrigin", false)
        }.toString()
        val clientDataBytes = clientDataJsonString.toByteArray(Charsets.UTF_8)
        val clientDataBase64Url = clientDataBytes.toBase64Url()

        // 6. Compute SHA-256 over clientDataJSON
        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataBytes)

        // 7. Resolve hardware private key and prepare Signature instance
        val privateKey = keyStoreProvider.getPrivateKey(record.keyAlias)
            ?: throw FidoAuthenticationException("Hardware private key not found for alias: ${record.keyAlias}")

        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
        }

        val cryptoObject = try {
            BiometricPrompt.CryptoObject(signature)
        } catch (_: Exception) {
            null
        }

        // 8. Prompt biometrics to authorize enclave signing operation
        val authResult = biometricPromptProvider.authenticate(
            activity = activity,
            title = "Sign in with Passkey",
            subtitle = "Confirm your biometrics to authenticate with ${record.rpId}",
            cryptoObject = cryptoObject
        )

        // 9. Sign concatenated authData || sha256(clientDataJSON)
        val activeSignature = authResult.cryptoObject?.signature ?: signature
        activeSignature.update(authDataBytes)
        activeSignature.update(clientDataHash)
        val signatureBytes = activeSignature.sign()
        val signatureBase64Url = signatureBytes.toBase64Url()

        // 10. Return standard PublicKeyCredential assertion wire payload
        return buildJsonObject {
            put("id", record.credentialId)
            put("rawId", record.credentialId)
            put("type", "public-key")
            put("response", buildJsonObject {
                put("authenticatorData", authDataBase64Url)
                put("clientDataJSON", clientDataBase64Url)
                put("signature", signatureBase64Url)
                put("userHandle", record.userHandle)
            })
        }
    }

    /**
     * Retrieves all passkey credentials enrolled in this internal authenticator.
     */
    fun getAllCredentials(): List<EnrolledPasskeyRecord> {
        val storedJson = prefs.getString("records", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<EnrolledPasskeyRecord>>(storedJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Checks if at least one passkey credential is enrolled locally.
     */
    fun hasEnrolledCredentials(): Boolean = getAllCredentials().isNotEmpty()

    /**
     * Removes an enrolled credential from storage and deletes its keypair from the hardware enclave.
     *
     * @param credentialId Unpadded Base64URL credential identifier.
     */
    fun deleteCredential(credentialId: String) {
        val cleanId = credentialId.trim()
        val current = getAllCredentials()
        val target = current.firstOrNull { record ->
            record.credentialId == cleanId ||
                    base64UrlToHex(record.credentialId).equals(cleanId, ignoreCase = true) ||
                    hexToBase64Url(cleanId) == record.credentialId
        }
        if (target != null) {
            keyStoreProvider.deleteKey(target.keyAlias)
            val updated = current.filter { it.credentialId != target.credentialId }
            prefs.edit {
                putString("records", json.encodeToString(updated))
            }
        }
    }

    /**
     * Clears all local credential records and deletes corresponding enclave keys.
     */
    fun clearAllCredentials() {
        val current = getAllCredentials()
        for (record in current) {
            keyStoreProvider.deleteKey(record.keyAlias)
        }
        prefs.edit {
            remove("records")
        }
    }

    private fun saveCredential(record: EnrolledPasskeyRecord) {
        // Enforce that only the passkey of the last signed-in account is held locally
        val current = getAllCredentials()
        for (existing in current) {
            if (existing.keyAlias != record.keyAlias) {
                try {
                    keyStoreProvider.deleteKey(existing.keyAlias)
                } catch (_: Exception) {}
            }
        }
        prefs.edit {
            putString("records", json.encodeToString(listOf(record)))
        }
    }

    private fun incrementAndGetCounter(credentialId: String): Int {
        val current = getAllCredentials()
        val record = current.firstOrNull { it.credentialId == credentialId }
            ?: return 1
        val newCount = record.signCount + 1
        val updatedRecord = record.copy(signCount = newCount)
        val updatedList = current.map { if (it.credentialId == credentialId) updatedRecord else it }
        prefs.edit {
            putString("records", json.encodeToString(updatedList))
        }
        return newCount
    }

    private fun findMatchingCredential(
        allowCredentials: JsonArray?,
        rpId: String
    ): EnrolledPasskeyRecord? {
        val all = getAllCredentials()
        if (all.isEmpty()) return null

        if (allowCredentials != null && allowCredentials.isNotEmpty()) {
            val allowedIds = allowCredentials.mapNotNull { element ->
                (element as? JsonObject)?.extractString("id")?.trim()
            }.toSet()

            val matched = all.firstOrNull { record ->
                record.credentialId in allowedIds ||
                        allowedIds.any { allowed ->
                            base64UrlToHex(record.credentialId).equals(allowed, ignoreCase = true) ||
                                    hexToBase64Url(allowed) == record.credentialId
                        }
            }
            if (matched != null) return matched
        }

        // Discoverable resident key fallback: return the passkey of the last signed-in account (most recently enrolled)
        return all.maxByOrNull { it.createdAt } ?: all.firstOrNull()
    }

    private fun parseChallengeJson(rawJson: String): JsonObject {
        val element = try {
            json.parseToJsonElement(rawJson)
        } catch (e: Exception) {
            throw FidoAuthenticationException("Failed to parse challenge JSON: ${e.message}", e)
        }

        return when (element) {
            is JsonObject -> {
                val pk = element["publicKey"]
                if (pk is JsonObject) pk else element
            }
            else -> throw FidoAuthenticationException("Expected JsonObject for challenge parameters")
        }
    }

    private fun JsonObject.extractString(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else primitive.toString()
    }

    private fun JsonObject.extractObject(key: String): JsonObject? {
        return this[key] as? JsonObject
    }

    private fun JsonObject.extractArray(key: String): JsonArray? {
        return this[key] as? JsonArray
    }

    private fun resolveApkKeyHashOrigin(): String {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageInfo.signingInfo
                if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            val certBytes = signers?.firstOrNull()?.toByteArray()
            if (certBytes != null) {
                val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
                val b64 = digest.toBase64Url()
                return "android:apk-key-hash:$b64"
            }
        } catch (_: Exception) {
            // Fall through to deterministic default hash
        }

        // Shared debug keystore SHA-256 Base64URL fallback
        return DEFAULT_DEBUG_ORIGIN
    }

    companion object {
        /**
         * Deterministic Base64URL SHA-256 origin corresponding to `app-gallery/kosh-dev.keystore`.
         * Colon Hex: `E5:F9:3A:2D:E3:BB:A1:BC:49:19:63:1B:6C:65:9C:25:2F:AB:FC:D5:48:74:7A:1B:B5:14:B9:34:58:DF:BE:58`
         */
        const val DEFAULT_DEBUG_ORIGIN =
            "android:apk-key-hash:5fk6LeO7obxJGWMbbGWcJS-r_NVIdHobtRS5NFjfvlg"

        /**
         * Encodes a [ByteArray] into an unpadded URL-safe Base64 string per RFC 4648 Section 5.
         */
        fun ByteArray.toBase64Url(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(this)

        /**
         * Decodes an unpadded or padded URL-safe Base64 string into a [ByteArray].
         */
        fun String.fromBase64Url(): ByteArray =
            Base64.getUrlDecoder().decode(this.trim())

        /**
         * Converts a Base64URL string to a lowercase hexadecimal string.
         */
        fun base64UrlToHex(base64Url: String): String? = try {
            val bytes = base64Url.fromBase64Url()
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }

        /**
         * Converts a hexadecimal string into an unpadded Base64URL string.
         */
        fun hexToBase64Url(hex: String): String? = try {
            val clean = hex.trim()
            if (clean.length % 2 != 0) {
                null
            } else {
                val bytes = ByteArray(clean.length / 2)
                for (i in clean.indices step 2) {
                    val d1 = Character.digit(clean[i], 16)
                    val d2 = Character.digit(clean[i + 1], 16)
                    if (d1 == -1 || d2 == -1) return null
                    bytes[i / 2] = ((d1 shl 4) or d2).toByte()
                }
                bytes.toBase64Url()
            }
        } catch (_: Exception) {
            null
        }

        private fun Context.findFragmentActivity(): FragmentActivity? {
            var ctx: Context? = this
            while (ctx is ContextWrapper) {
                if (ctx is FragmentActivity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }
    }
}
