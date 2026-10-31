package project.kosh.core.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import project.kosh.core.model.AuthCredential
import java.nio.charset.Charset
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cryptographic service responsible for client-side zero-knowledge identity and credential derivation.
 *
 * In Kosh's edge-first architecture, the Android mobile client acts as "The Brain", holding master
 * encryption keys and private credentials, while the remote Rust server acts as an untrusted store.
 * Plaintext passwords and master keys never leave the Android runtime.
 *
 * This manager derives two distinct cryptographic primitives from the user's raw inputs:
 * 1. **Identity Hash:** A deterministic SHA-256 digest of the username (formatted as 64 lowercase
 *    hexadecimal characters) that serves as the account identifier on the backend and the subject of
 *    Hashcash Proof-of-Work challenges.
 * 2. **Authentication Verifier:** A password verifier generated via the memory-hard Argon2id key
 *    derivation function (KDF). The server stores and matches this verifier verbatim without re-hashing,
 *    keeping backend CPU utilization minimal while guaranteeing that brute-force attacks must be
 *    computed client-side.
 *
 * ### Threading Boundary & Main Looper Protection
 * Computing Argon2id with 64 MiB of memory and 3 iterations consumes between 300ms and 800ms of intensive
 * CPU and memory allocation on modern ARM SoCs (Google Tensor, Qualcomm Snapdragon). Invoking
 * [deriveCredentials] on Android's Main Looper thread will block UI touch events, drop frames, and trigger
 * Application Not Responding (ANR) warnings. Callers **must** execute this operation on `Dispatchers.Default`.
 */
@Singleton
class IdentityManager @Inject constructor() {
    private val argon2 = Argon2Kt()

    /**
     * Derives an [AuthCredential] pair from the user's plaintext [username] and [passphrase].
     *
     * This method executes CPU- and memory-intensive operations (Argon2id and SHA-256).
     * Callers must invoke this function on a background coroutine dispatcher (`Dispatchers.Default`).
     *
     * @param username The user's account name (converted to UTF-8 bytes for identity hashing).
     * @param passphrase The user's secret passphrase (converted to UTF-8 bytes for Argon2id key derivation).
     * @return The derived [AuthCredential] containing the identity hash and Argon2id verifier.
     */
    fun deriveCredentials(username: String, passphrase: String): AuthCredential {
        val passBytes = passphrase.toByteArray(Charsets.UTF_8)
        val nameBytes = username.toByteArray(Charsets.UTF_8)

        return AuthCredential(
            computeIdentityHash(nameBytes),
            computeAuthVerifier(passBytes)
        )
    }

    /**
     * Computes a 64-character lowercase hexadecimal SHA-256 digest over the provided [input] bytes.
     *
     * @param input Raw bytes of the username.
     * @return A 64-character lowercase hex string representing the identity hash.
     */
    private fun computeIdentityHash(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input)

        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes the password authentication verifier using Argon2id.
     *
     * Cryptographic parameters:
     * - **Mode:** Argon2id (hybrid mode combining data-dependent and data-independent memory access)
     * - **Memory Cost (`m`):** 65,536 KiB (64 MiB RAM footprint)
     * - **Time Cost (`t`):** 3 iterations
     * - **Parallelism (`p`):** 1 lane/thread
     * - **Output Length:** 32 bytes
     *
     * @param input Raw UTF-8 bytes of the user's passphrase.
     * @return Standard Argon2 encoded string representing the verifier.
     */
    private fun computeAuthVerifier(input : ByteArray): String {
        val salt = "kosh_salty".toByteArray(Charsets.UTF_8)

        val result = argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = input,
            salt = salt,
            tCostInIterations = 3,
            mCostInKibibyte = 65536,
            parallelism = 1,
            hashLengthInBytes = 32,
        )

        return result.encodedOutputAsString()
    }

    fun deriveKek(passphrase: String): ByteArray {
        val passBytes = passphrase.toByteArray(Charsets.UTF_8)
        val kekSalt = "kosh_vault".toByteArray(Charsets.UTF_8)

        val result = argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = passBytes,
            salt = kekSalt,
            tCostInIterations = 3,
            mCostInKibibyte = 65536,
            parallelism = 1,
            hashLengthInBytes = 32
        )

        return result.rawHashAsByteArray()
    }
}