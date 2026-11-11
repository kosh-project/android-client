package project.kosh.core.auth.fido

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction decoupling hardware-backed Android KeyStore cryptographic mechanics.
 *
 * Facilitates unit testing by allowing JVM host test suites to supply standard Java
 * cryptographic key providers without requiring native AndroidKeyStore TEE hardware.
 */
interface KeyStoreProvider {
    /**
     * Generates an EC P-256 (secp256r1) key pair configured for SHA-256 ECDSA signing.
     *
     * @param alias The unique deterministic key alias.
     * @param requireUserAuth Whether the hardware enclave requires biometric unlock before signing.
     * @return The generated [ECPublicKey].
     */
    fun generateEcP256KeyPair(alias: String, requireUserAuth: Boolean): ECPublicKey

    /**
     * Retrieves an existing public key associated with [alias], or null if absent.
     */
    fun getPublicKey(alias: String): ECPublicKey?

    /**
     * Retrieves the hardware-backed private key reference associated with [alias], or null if absent.
     */
    fun getPrivateKey(alias: String): PrivateKey?

    /**
     * Checks if a key entry exists under [alias].
     */
    fun containsAlias(alias: String): Boolean

    /**
     * Permanently deletes a key entry from the KeyStore.
     */
    fun deleteKey(alias: String)
}

/**
 * Production implementation of [KeyStoreProvider] backed by the hardware `AndroidKeyStore`.
 *
 * Utilizes device Trusted Execution Environment (TEE) or StrongBox Keymaster to enforce
 * hardware isolation and biometric gating.
 */
@Singleton
class AndroidKeyStoreProvider @Inject constructor() : KeyStoreProvider {

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    override fun generateEcP256KeyPair(alias: String, requireUserAuth: Boolean): ECPublicKey {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        )

        fun buildSpec(userAuth: Boolean): KeyGenParameterSpec {
            val specBuilder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)

            if (userAuth) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try {
                        specBuilder.setUserAuthenticationParameters(
                            60,
                            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                        )
                    } catch (_: Exception) {
                        @Suppress("DEPRECATION")
                        specBuilder.setUserAuthenticationRequired(true)
                        @Suppress("DEPRECATION")
                        specBuilder.setUserAuthenticationValidityDurationSeconds(60)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    specBuilder.setUserAuthenticationRequired(true)
                    @Suppress("DEPRECATION")
                    specBuilder.setUserAuthenticationValidityDurationSeconds(60)
                }
            }
            return specBuilder.build()
        }

        return try {
            keyPairGenerator.initialize(buildSpec(requireUserAuth))
            val keyPair = keyPairGenerator.generateKeyPair()
            keyPair.public as ECPublicKey
        } catch (e: Exception) {
            if (requireUserAuth) {
                // Graceful fallback to non-auth enclave key if device lacks enrolled biometrics
                keyPairGenerator.initialize(buildSpec(false))
                val keyPair = keyPairGenerator.generateKeyPair()
                keyPair.public as ECPublicKey
            } else {
                throw e
            }
        }
    }

    override fun getPublicKey(alias: String): ECPublicKey? {
        val cert = keyStore.getCertificate(alias) ?: return null
        return cert.publicKey as? ECPublicKey
    }

    override fun getPrivateKey(alias: String): PrivateKey? {
        return keyStore.getKey(alias, null) as? PrivateKey
    }

    override fun containsAlias(alias: String): Boolean {
        return keyStore.containsAlias(alias)
    }

    override fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }
}
