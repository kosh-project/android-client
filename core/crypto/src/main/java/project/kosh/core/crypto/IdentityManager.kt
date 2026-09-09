package project.kosh.core.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import project.kosh.core.model.AuthCredential
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityManager @Inject constructor() {
    private val argon2 = Argon2Kt()


    fun deriveCredentials(username: String, passphrase: String): AuthCredential {
        val passBytes = passphrase.toByteArray(Charsets.UTF_8)
        val nameBytes = username.toByteArray(Charsets.UTF_8)

        return AuthCredential(
            computeIdentityHash(nameBytes),
            computeAuthVerifier(passBytes)
        )
    }

    private fun computeIdentityHash(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input)

        return hashBytes.joinToString("") { "%02x".format(it) }
    }

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
}