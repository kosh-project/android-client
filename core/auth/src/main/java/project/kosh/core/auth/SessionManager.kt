package project.kosh.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.media.session.MediaSession
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.network.TokenProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware-backed secure session storage service implementing [TokenProvider].
 *
 * Persists the 118-byte structured bearer token issued by the Kosh server using authenticated
 * AES-256-GCM encryption backed by the AndroidKeyStore provider.
 *
 * ### Hardware Security & Key Isolation
 * The master encryption key is generated directly within the device's Trusted Execution Environment
 * (TEE) or Secure Element via [AndroidKeyStore]. Key material is non-exportable and never enters
 * the Android application memory space in unencrypted form.
 *
 * ### Cryptographic Pipeline
 * - **Algorithm:** AES-256 in Galois/Counter Mode (`AES/GCM/NoPadding`).
 * - **Initialization Vector (IV):** Generated dynamically by the cipher provider for every encryption
 *   call to guarantee nonce uniqueness and prevent replay or differential cryptanalysis.
 * - **Integrity Tag:** 128-bit GCM authentication tag verifying ciphertext integrity against tampering.
 * - **Persistence:** Stored in private [SharedPreferences] encoded as Base64 strings.
 *
 * @param context Android application context used to access private shared preferences.
 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) : TokenProvider {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val keyAlias = "auth_master_key"

    init {
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val keySpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(keySpec)
            keyGenerator.generateKey()
        }
    }

    /**
     * Resolves the hardware-backed [SecretKey] from the AndroidKeyStore.
     *
     * @return The 256-bit AES master key reference.
     */
    private fun getSecretKey(): SecretKey {
        return keyStore.getKey(keyAlias, null) as SecretKey
    }

    /**
     * Encrypts and persists the given bearer [token] in hardware-encrypted storage.
     *
     * Generates a fresh random IV, encrypts the token bytes using AES-256-GCM, and stores
     * both the IV and ciphertext as Base64 strings in shared preferences.
     *
     * @param token The raw 118-byte composite bearer token issued by the server.
     */
    fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

        prefs.edit {
            putString("auth_iv", Base64.encodeToString(iv, Base64.DEFAULT))
            putString("auth_token_enc", Base64.encodeToString(ciphertext, Base64.DEFAULT))
        }
    }

    /**
     * Decrypts and retrieves the active session token from secure storage.
     *
     * Reads the Base64-encoded IV and ciphertext, initializes an AES-256-GCM cipher with
     * the hardware-backed key, and decrypts the payload. If the ciphertext has been altered,
     * the GCM tag verification will fail and return `null`.
     *
     * @return The decrypted bearer token string, or `null` if no token is saved or decryption fails.
     */
    override fun getToken(): String? {
        val ivBase64 = prefs.getString("auth_iv", null) ?: return null
        val ciphertextBase64 = prefs.getString("auth_token_enc", null) ?: return null

        val iv = Base64.decode(ivBase64, Base64.DEFAULT)
        val ciphertext = Base64.decode(ciphertextBase64, Base64.DEFAULT)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)

        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

        return try {
            val decodedBytes = cipher.doFinal(ciphertext)
            String(decodedBytes, Charsets.UTF_8)
        } catch (e : Exception) {
            null
        }
    }

    /**
     * Encrypts and securely persists the 32-byte Master Vault Key using the hardware-backed KeyStore key.
     *
     * @param vaultKey The active 32-byte Master Vault Key.
     */
    fun saveVaultKey(vaultKey: MasterVaultKey) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(vaultKey.keyBytes)

        prefs.edit {
            putString("vault_key_iv", Base64.encodeToString(iv, Base64.DEFAULT))
            putString("vault_key_enc", Base64.encodeToString(ciphertext, Base64.DEFAULT))
        }
    }

    /**
     * Decrypts and retrieves the active 32-byte Master Vault Key from hardware-backed storage.
     *
     * @return The decrypted [MasterVaultKey], or `null` if not found or verification fails.
     */
    fun getVaultKey(): MasterVaultKey? {
        val ivBase64 = prefs.getString("vault_key_iv", null) ?: return null
        val ciphertextBase64 = prefs.getString("vault_key_enc", null) ?: return null

        val iv = Base64.decode(ivBase64, Base64.DEFAULT)
        val ciphertext = Base64.decode(ciphertextBase64, Base64.DEFAULT)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)

        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

        return try {
            val keyBytes = cipher.doFinal(ciphertext)
            MasterVaultKey(keyBytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Persists the active user handle or username in secure storage.
     *
     * @param username Account identifier.
     */
    fun saveUsername(username: String) {
        prefs.edit {
            putString("active_user", username)
        }
    }

    /**
     * Resolves the persisted username, or null if unauthenticated.
     */
    fun getUsername(): String? {
        return prefs.getString("active_user", null)
    }

    /**
     * Clears all stored session credentials and vault keys from local storage.
     *
     * Invoked during user logout or when the server responds with HTTP 401 Unauthorized
     * (e.g. token expiration or server reboot with rotated signing secret) to prevent
     * infinite 401 retry loops.
     */
    fun clearToken() {
        prefs.edit {
            remove("auth_iv")
            remove("auth_token_enc")
            remove("vault_key_iv")
            remove("vault_key_enc")
            remove("active_user")
        }
    }
}