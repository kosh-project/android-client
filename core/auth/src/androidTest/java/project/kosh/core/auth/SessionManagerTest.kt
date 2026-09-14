package project.kosh.core.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


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
}