package project.kosh.core.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import project.kosh.core.crypto.MasterVaultKey


@RunWith(AndroidJUnit4::class)
class SessionManagerTest {
    private lateinit var context: Context
    private lateinit var sessionManager: SessionManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        sessionManager = SessionManager(context)

        context.getSharedPreferences("session", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @After
    fun teardown() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @Test
    fun testTokenEncryptionandDecryption() {
        val originalToken = "this_is_my_token"

        assertNull("Token should be null on fresh setup", sessionManager.getToken())

        sessionManager.saveToken(originalToken)

        val decryptedToken = sessionManager.getToken()
        assertEquals("Decrypted token doesn't match original", originalToken, decryptedToken)

        val rawPref = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        val rawEncryptedString = rawPref.getString("auth_token_enc", null)

        assertNotNull("Encrypted string should exist on disk", rawEncryptedString)

        assertFalse("CRITICAL_SECURITY_FAILURE: Token exists in plaintext format", rawEncryptedString!!.contains(originalToken))
    }

    @Test
    fun testVaultKeyCloningProtectsCachedKeyAgainstExternalClose() {
        val originalKeyBytes = ByteArray(32) { (it + 1).toByte() }
        val originalKey = MasterVaultKey(originalKeyBytes)

        sessionManager.saveVaultKey(originalKey)

        // Close external key reference
        originalKey.close()
        assertTrue("Original key should be marked closed", originalKey.isClosed)

        // Cached key in SessionManager must remain valid and unclosed
        val retrievedKey = sessionManager.getVaultKey()
        assertNotNull("Retrieved key must not be null", retrievedKey)
        assertFalse("Retrieved key must not be closed", retrievedKey!!.isClosed)
        assertArrayEquals(
            "Retrieved key bytes must match original unzeroed bytes",
            ByteArray(32) { (it + 1).toByte() },
            retrievedKey.keyBytes
        )
    }
}