package project.kosh.core.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class SessionManagerTamperTest {
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

    @Test
    fun testTamperedTokenReturnsNull() {
        sessionManager.saveToken("valid_user_token")

        val rawPrefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        val encryptedBase64 = rawPrefs.getString("auth_token_enc", null)!!

        val tamperedString = if (encryptedBase64.startsWith("A")) {
            "B" + encryptedBase64.substring(1)
        } else {
            "A" + encryptedBase64.substring(1)
        }

        rawPrefs.edit().putString("auth_token_enc", tamperedString).commit()

        val result = sessionManager.getToken()

        assertNull(
            "CRITICAL SECURITY FAILURE: SessionManager decrypted tampered data or crashed",
            result
        )
    }

    @After
    fun teardown() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }
}