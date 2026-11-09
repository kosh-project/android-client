package project.kosh.core.auth.fido

import android.content.Context
import android.content.SharedPreferences
import androidx.fragment.app.FragmentActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import project.kosh.core.model.PasskeyChallengeResponse
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class InternalFidoAuthenticatorTest {

    private lateinit var context: Context
    private lateinit var activity: FragmentActivity
    private lateinit var keyStoreProvider: KeyStoreProvider
    private lateinit var biometricPromptProvider: BiometricPromptProvider
    private lateinit var prefs: SharedPreferences
    private lateinit var authenticator: InternalFidoAuthenticator

    private lateinit var ecPublicKey: ECPublicKey
    private lateinit var ecPrivateKey: PrivateKey
    private val inMemoryPrefs = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()
        ecPublicKey = keyPair.public as ECPublicKey
        ecPrivateKey = keyPair.private

        context = mockk(relaxed = true)
        activity = mockk(relaxed = true)
        keyStoreProvider = mockk(relaxed = true)
        biometricPromptProvider = mockk(relaxed = true)
        prefs = mockk(relaxed = true)

        inMemoryPrefs.clear()
        every { prefs.getString("records", null) } answers { inMemoryPrefs["records"] }
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            inMemoryPrefs[firstArg()] = secondArg()
            editor
        }
        every { editor.remove(any()) } answers {
            inMemoryPrefs.remove(firstArg())
            editor
        }
        every { editor.apply() } returns Unit

        every { context.getSharedPreferences(any(), any()) } returns prefs

        // KeyStore mock
        every { keyStoreProvider.generateEcP256KeyPair(any(), any()) } returns ecPublicKey
        every { keyStoreProvider.getPublicKey(any()) } returns ecPublicKey
        every { keyStoreProvider.getPrivateKey(any()) } returns ecPrivateKey

        // Biometric mock: success by default, preserving the CryptoObject passed in
        coEvery {
            biometricPromptProvider.authenticate(any(), any(), any(), any())
        } answers {
            val crypto = firstArg<androidx.fragment.app.FragmentActivity>().let {
                arg<androidx.biometric.BiometricPrompt.CryptoObject?>(3)
            }
            val result = mockk<androidx.biometric.BiometricPrompt.AuthenticationResult>(relaxed = true)
            every { result.cryptoObject } returns crypto
            result
        }

        authenticator = InternalFidoAuthenticator(
            context = context,
            keyStoreProvider = keyStoreProvider,
            biometricPromptProvider = biometricPromptProvider
        )
    }

    private fun createSampleRegistrationChallenge(): PasskeyChallengeResponse {
        val challengeJson = buildJsonObject {
            put("publicKey", buildJsonObject {
                put("rp", buildJsonObject {
                    put("name", "Kosh Local Server")
                    put("id", "kosh.local")
                })
                put("user", buildJsonObject {
                    put("id", "dXNlcl91dWlkXzEyMzQ")
                    put("name", "user@kosh.local")
                    put("displayName", "Kosh Developer")
                })
                put("challenge", "dGVzdF9jaGFsbGVuZ2VfdG9rZW4")
                put("timeout", 60000)
            })
        }
        return PasskeyChallengeResponse("challenge_uuid_reg", challengeJson)
    }

    private fun createSampleLoginChallenge(): PasskeyChallengeResponse {
        val challengeJson = buildJsonObject {
            put("publicKey", buildJsonObject {
                put("challenge", "bG9naW5fY2hhbGxlbmdlX3Rva2Vu")
                put("rpId", "kosh.local")
                put("timeout", 60000)
                put("userVerification", "preferred")
            })
        }
        return PasskeyChallengeResponse("challenge_uuid_login", challengeJson)
    }

    @Test
    fun `registerCredential generates key, constructs valid clientDataJSON and attestationObject`() {
        runBlocking {
            val challenge = createSampleRegistrationChallenge()
            val result = authenticator.registerCredential(activity, challenge)

            assertTrue(result is JsonObject)
            val credObj = result as JsonObject
            assertEquals("public-key", credObj["type"]?.jsonPrimitive?.content)

            val id = credObj["id"]?.jsonPrimitive?.content
            val rawId = credObj["rawId"]?.jsonPrimitive?.content
            assertNotNull(id)
            assertEquals(id, rawId)

            val response = credObj["response"] as JsonObject
            val attestationObjectB64 = response["attestationObject"]?.jsonPrimitive?.content
            val clientDataJsonB64 = response["clientDataJSON"]?.jsonPrimitive?.content
            assertNotNull(attestationObjectB64)
            assertNotNull(clientDataJsonB64)

            // Verify clientDataJSON content
            val clientDataBytes = Base64.getUrlDecoder().decode(clientDataJsonB64)
            val clientDataJson = Json.parseToJsonElement(String(clientDataBytes, Charsets.UTF_8)) as JsonObject
            assertEquals("webauthn.create", clientDataJson["type"]?.jsonPrimitive?.content)
            assertEquals("dGVzdF9jaGFsbGVuZ2VfdG9rZW4", clientDataJson["challenge"]?.jsonPrimitive?.content)
            assertEquals("http://localhost:23342", clientDataJson["origin"]?.jsonPrimitive?.content)

            // Verify attestationObject bytes
            val attestationBytes = Base64.getUrlDecoder().decode(attestationObjectB64)
            // 0xa3: Map of 3 ("fmt", "attStmt", "authData")
            assertEquals(0xa3.toByte(), attestationBytes[0])

            // Verify credential was persisted locally
            val enrolled = authenticator.getAllCredentials()
            assertEquals(1, enrolled.size)
            assertEquals(id, enrolled[0].credentialId)
            assertEquals("kosh.local", enrolled[0].rpId)
            assertEquals("dXNlcl91dWlkXzEyMzQ", enrolled[0].userHandle)
            assertEquals(0, enrolled[0].signCount)
        }
    }

    @Test
    fun `authenticateCredential generates 37-byte authData and verifiable ECDSA-SHA256 signature`() {
        runBlocking {
            // First enroll a credential
            val regChallenge = createSampleRegistrationChallenge()
            val regResult = authenticator.registerCredential(activity, regChallenge) as JsonObject
            val enrolledCredId = regResult["id"]!!.jsonPrimitive.content

            // Now execute assertion login
            val loginChallenge = createSampleLoginChallenge()
            val result = authenticator.authenticateCredential(activity, loginChallenge)

            assertTrue(result is JsonObject)
            val assertionObj = result as JsonObject
            assertEquals("public-key", assertionObj["type"]?.jsonPrimitive?.content)
            assertEquals(enrolledCredId, assertionObj["id"]?.jsonPrimitive?.content)

            val response = assertionObj["response"] as JsonObject
            val authDataB64 = response["authenticatorData"]?.jsonPrimitive?.content
            val clientDataJsonB64 = response["clientDataJSON"]?.jsonPrimitive?.content
            val signatureB64 = response["signature"]?.jsonPrimitive?.content
            val userHandleB64 = response["userHandle"]?.jsonPrimitive?.content

            assertNotNull(authDataB64)
            assertNotNull(clientDataJsonB64)
            assertNotNull(signatureB64)
            assertEquals("dXNlcl91dWlkXzEyMzQ", userHandleB64)

            // Verify authenticatorData layout (37 bytes)
            val authDataBytes = Base64.getUrlDecoder().decode(authDataB64)
            assertEquals(37, authDataBytes.size)
            // Verify flags: 0x05 (UP=1, UV=1)
            assertEquals(0x05.toByte(), authDataBytes[32])
            // Verify counter: incremented to 1
            val counter = (authDataBytes[33].toInt() and 0xff shl 24) or
                    (authDataBytes[34].toInt() and 0xff shl 16) or
                    (authDataBytes[35].toInt() and 0xff shl 8) or
                    (authDataBytes[36].toInt() and 0xff)
            assertEquals(1, counter)

            // Verify clientDataJSON
            val clientDataBytes = Base64.getUrlDecoder().decode(clientDataJsonB64)
            val clientDataJson = Json.parseToJsonElement(String(clientDataBytes, Charsets.UTF_8)) as JsonObject
            assertEquals("webauthn.get", clientDataJson["type"]?.jsonPrimitive?.content)
            assertEquals("bG9naW5fY2hhbGxlbmdlX3Rva2Vu", clientDataJson["challenge"]?.jsonPrimitive?.content)

            // Cryptographically verify the ECDSA signature against ecPublicKey
            val signatureBytes = Base64.getUrlDecoder().decode(signatureB64)
            val clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataBytes)

            val verifier = Signature.getInstance("SHA256withECDSA").apply {
                initVerify(ecPublicKey)
                update(authDataBytes)
                update(clientDataHash)
            }
            val isSignatureValid = verifier.verify(signatureBytes)
            assertTrue("Assertion signature must be mathematically valid under ecPublicKey", isSignatureValid)
        }
    }

    @Test(expected = BiometricCancelledException::class)
    fun `registerCredential propagates BiometricCancelledException when user dismisses prompt`() {
        runBlocking {
            coEvery {
                biometricPromptProvider.authenticate(any(), any(), any(), any())
            } throws BiometricCancelledException("User dismissed biometric prompt")

            val challenge = createSampleRegistrationChallenge()
            authenticator.registerCredential(activity, challenge)
        }
    }

    @Test(expected = FidoAuthenticationException::class)
    fun `authenticateCredential throws when no enrolled passkey exists on device`() {
        runBlocking {
            val loginChallenge = createSampleLoginChallenge()
            authenticator.authenticateCredential(activity, loginChallenge)
        }
    }

    @Test
    fun `deleteCredential deletes key and record when given Base64URL ID`() = runBlocking {
        val regChallenge = createSampleRegistrationChallenge()
        val regResult = authenticator.registerCredential(activity, regChallenge) as JsonObject
        val enrolledCredId = regResult["id"]!!.jsonPrimitive.content

        assertEquals(1, authenticator.getAllCredentials().size)
        authenticator.deleteCredential(enrolledCredId)

        assertEquals(0, authenticator.getAllCredentials().size)
        io.mockk.verify(atLeast = 1) { keyStoreProvider.deleteKey("kosh_passkey_$enrolledCredId") }
    }

    @Test
    fun `deleteCredential deletes key and record when given Hex ID from server`() = runBlocking {
        val regChallenge = createSampleRegistrationChallenge()
        val regResult = authenticator.registerCredential(activity, regChallenge) as JsonObject
        val enrolledCredId = regResult["id"]!!.jsonPrimitive.content

        val hexId = InternalFidoAuthenticator.base64UrlToHex(enrolledCredId)
        assertNotNull(hexId)

        assertEquals(1, authenticator.getAllCredentials().size)
        authenticator.deleteCredential(hexId!!)

        assertEquals(0, authenticator.getAllCredentials().size)
        io.mockk.verify(atLeast = 1) { keyStoreProvider.deleteKey("kosh_passkey_$enrolledCredId") }
    }

    @Test
    fun `authenticateCredential finds matching credential when allowCredentials contains Hex ID`() = runBlocking {
        val regChallenge = createSampleRegistrationChallenge()
        val regResult = authenticator.registerCredential(activity, regChallenge) as JsonObject
        val enrolledCredId = regResult["id"]!!.jsonPrimitive.content

        val hexId = InternalFidoAuthenticator.base64UrlToHex(enrolledCredId)!!

        val loginChallengeJson = buildJsonObject {
            put("publicKey", buildJsonObject {
                put("challenge", "bG9naW5fY2hhbGxlbmdlX3Rva2Vu")
                put("rpId", "different.local")
                put("allowCredentials", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("type", "public-key")
                        put("id", hexId)
                    })
                })
            })
        }
        val loginChallenge = PasskeyChallengeResponse("challenge_uuid_login", loginChallengeJson)

        val result = authenticator.authenticateCredential(activity, loginChallenge) as JsonObject
        assertEquals(enrolledCredId, result["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `resolveOriginForRp aligns with backend origin for local and kosh domains`() {
        assertEquals("http://localhost:23342", authenticator.resolveOriginForRp("localhost"))
        assertEquals("http://localhost:23342", authenticator.resolveOriginForRp("127.0.0.1"))
        assertEquals("http://localhost:23342", authenticator.resolveOriginForRp("kosh.local"))
        assertEquals("http://localhost:23342", authenticator.resolveOriginForRp("my-kosh-node.local"))
        assertEquals("http://localhost:23342", authenticator.resolveOriginForRp("192.168.1.100"))
        assertEquals("http://localhost:23342", authenticator.resolveOriginForRp("10.0.2.2"))
    }

    @Test
    fun `resolveOriginForRp uses apk-key-hash origin for public web domains`() {
        val resolved = authenticator.resolveOriginForRp("auth.example.com")
        assertTrue(resolved.startsWith("android:apk-key-hash:"))
    }
}

