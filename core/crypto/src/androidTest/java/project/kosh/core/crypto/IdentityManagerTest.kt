package project.kosh.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentityManagerTest {

    private val identityManager = IdentityManager()

    @Test
    fun testCryptoLockoutPrevention() {
        val username = "test_user"
        val password = "egg_password"

        val credentials = identityManager.deriveCredentials(username,password)

        val expectedHash = "1160130875fda0812c99c5e3f1a03516471a6370c4f97129b221938eb4763e63"
        Assert.assertEquals(
            "CRITICAL: SHA-256 algorithm affected! Users will be locked out",
            credentials.identityHash,
            expectedHash
        )

        val expectedArgon2 =
            $$"$argon2id$v=19$m=65536,t=3,p=1$a29zaF9zYWx0eQ$xqwKR2c2Mz69EdVLXjfOjPn9WRceWfEEa5jxFKvKlH0"
        Assert.assertEquals(
            "CRITICAL: Argon2id parameters (m, t, p, or salt) were affected! Users will be locked out!!",
            expectedArgon2,
            credentials.authVerifier
        )
    }
}