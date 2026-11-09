package project.kosh.core.auth

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import project.kosh.core.auth.fido.BiometricCancelledException
import project.kosh.core.auth.fido.FidoAuthenticationException
import project.kosh.core.auth.fido.InternalFidoAuthenticator
import project.kosh.core.crypto.AesGcmEnvelope
import project.kosh.core.crypto.IdentityManager
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.model.AuthResponse
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PasskeyChallengeResponse
import project.kosh.core.model.PasskeyLoginFinishRequest
import project.kosh.core.model.PasskeyMetadataRow
import project.kosh.core.model.PasskeyRegisterFinishRequest
import project.kosh.core.network.PasskeyClient

class PasskeyAuthManagerTest {

    private lateinit var context: Context
    private lateinit var passkeyClient: PasskeyClient
    private lateinit var sessionManager: SessionManager
    private lateinit var identityManager: IdentityManager
    private lateinit var internalAuthenticator: InternalFidoAuthenticator
    private lateinit var passkeyAuthManager: PasskeyAuthManager
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        passkeyClient = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        identityManager = mockk(relaxed = true)
        internalAuthenticator = mockk(relaxed = true)

        passkeyAuthManager = PasskeyAuthManager(
            context = context,
            passkeyClient = passkeyClient,
            sessionManager = sessionManager,
            identityManager = identityManager,
            internalAuthenticator = internalAuthenticator
        )
    }

    private fun createSampleRegistrationChallenge(): PasskeyChallengeResponse {
        val challengeJson = buildJsonObject {
            put("publicKey", buildJsonObject {
                put("rp", buildJsonObject {
                    put("name", "Kosh Storage Server")
                    put("id", "kosh.local")
                })
                put("user", buildJsonObject {
                    put("id", "dXNlcl9pZF8xMjM")
                    put("name", "user@kosh.local")
                    put("displayName", "thenil3sh")
                })
                put("challenge", "reg_challenge_token_bytes")
                put("timeout", 60000)
            })
        }
        return PasskeyChallengeResponse("reg_challenge_uuid_123", challengeJson)
    }

    private fun createSampleLoginChallenge(): PasskeyChallengeResponse {
        val challengeJson = buildJsonObject {
            put("publicKey", buildJsonObject {
                put("challenge", "login_challenge_bytes")
                put("rpId", "kosh.local")
                put("timeout", 60000)
                put("userVerification", "preferred")
            })
        }
        return PasskeyChallengeResponse("login_challenge_uuid_456", challengeJson)
    }

    // --- Metadata Encryption / Decryption Tests ---

    @Test
    fun `encryptMetadata and decryptMetadata roundtrip produces identical plaintext`() {
        val masterKey = MasterVaultKey.generate()
        val deviceNickname = "Pixel 8 Pro (Personal)"

        val encryptedHex = passkeyAuthManager.encryptMetadata(deviceNickname, masterKey)
        assertTrue(encryptedHex.isNotEmpty())
        assertTrue(encryptedHex.length % 2 == 0)

        val decrypted = passkeyAuthManager.decryptMetadata(encryptedHex, masterKey)
        assertEquals(deviceNickname, decrypted)
    }

    @Test
    fun `encryptMetadata with empty string returns empty string`() {
        val masterKey = MasterVaultKey.generate()
        val encryptedHex = passkeyAuthManager.encryptMetadata("", masterKey)
        assertEquals("", encryptedHex)
    }

    @Test
    fun `decryptMetadata with empty string returns empty string`() {
        val masterKey = MasterVaultKey.generate()
        val decrypted = passkeyAuthManager.decryptMetadata("", masterKey)
        assertEquals("", decrypted)
    }

    @Test
    fun `decryptMetadata with corrupted or tampered ciphertext returns null`() {
        val masterKey = MasterVaultKey.generate()
        val originalHex = passkeyAuthManager.encryptMetadata("Samsung Galaxy S24", masterKey)

        // Corrupt the ciphertext / tag
        val lastTwo = originalHex.takeLast(2)
        val replacement = if (lastTwo.equals("ff", ignoreCase = true)) "00" else "ff"
        val corruptedHex = originalHex.dropLast(2) + replacement
        val decrypted = passkeyAuthManager.decryptMetadata(corruptedHex, masterKey)
        assertNull(decrypted)
    }

    @Test
    fun `decryptMetadata with wrong key returns null`() {
        val keyA = MasterVaultKey.generate()
        val keyB = MasterVaultKey.generate()

        val encryptedHex = passkeyAuthManager.encryptMetadata("Pixel Tablet", keyA)
        val decrypted = passkeyAuthManager.decryptMetadata(encryptedHex, keyB)
        assertNull(decrypted)
    }

    @Test
    fun `decryptMetadata with invalid hex or short hex returns null`() {
        val masterKey = MasterVaultKey.generate()
        assertNull(passkeyAuthManager.decryptMetadata("odd_length", masterKey))
        assertNull(passkeyAuthManager.decryptMetadata("1234abcd", masterKey)) // Too short for IV + tag
    }

    @Test
    fun `decryptNickname with valid encrypted metadata returns decrypted device nickname`() {
        val masterKey = MasterVaultKey.generate()
        every { sessionManager.getVaultKey() } returns masterKey

        val deviceNickname = "Work Pixel 7a"
        val encryptedHex = passkeyAuthManager.encryptMetadata(deviceNickname, masterKey)

        val row = PasskeyMetadataRow(
            credentialId = "aabbccddeeff1122",
            encryptedMetadata = encryptedHex,
            createdAt = 1730000000L,
            lastUsed = 1730001000L
        )

        val resolved = passkeyAuthManager.decryptNickname(row, masterKey)
        assertEquals(deviceNickname, resolved)
    }

    @Test
    fun `decryptNickname with null vault key falls back to formatted credential ID`() {
        val row = PasskeyMetadataRow(
            credentialId = "aabbccddeeff1122",
            encryptedMetadata = "deadbeefcafebabe11223344",
            createdAt = 1730000000L,
            lastUsed = 0L
        )

        val resolved = passkeyAuthManager.decryptNickname(row, null)
        assertEquals("Passkey (aabbccdd)", resolved)
    }

    @Test
    fun `decryptNickname with corrupted metadata falls back to formatted credential ID`() {
        val masterKey = MasterVaultKey.generate()
        val row = PasskeyMetadataRow(
            credentialId = "aabbccddeeff1122",
            encryptedMetadata = "corrupted_metadata_blob",
            createdAt = 1730000000L,
            lastUsed = 0L
        )

        val resolved = passkeyAuthManager.decryptNickname(row, masterKey)
        assertEquals("Passkey (aabbccdd)", resolved)
    }

    // --- Passkey Registration Tests ---

    @Test
    fun `registerPasskey fails when no active session token exists in SessionManager`() = runBlocking {
        every { sessionManager.getToken() } returns null

        val result = passkeyAuthManager.registerPasskey(context, "Pixel 8")
        assertTrue(result is PasskeyRegisterResult.Error)
        val error = result as PasskeyRegisterResult.Error
        assertTrue(error.message.contains("Active session token required"))
    }

    @Test
    fun `registerPasskey fails when startRegistration returns NetworkResult Failure`() = runBlocking {
        every { sessionManager.getToken() } returns "valid_token"
        coEvery { passkeyClient.startRegistration("valid_token") } returns NetworkResult.Http.Unauthorized(
            serverMessage = "Session expired",
            isChallengeExpired = false
        )

        val result = passkeyAuthManager.registerPasskey(context, "Pixel 8")
        assertTrue(result is PasskeyRegisterResult.Error)
        val error = result as PasskeyRegisterResult.Error
        assertTrue(error.message.contains("Invalid credentials or session expired"))
    }

    @Test
    fun `registerPasskey succeeds and submits attestation and encrypted metadata`() = runBlocking {
        val token = "active_session_token"
        every { sessionManager.getToken() } returns token

        val masterKey = MasterVaultKey.generate()
        every { sessionManager.getVaultKey() } returns masterKey

        val challengeResponse = createSampleRegistrationChallenge()
        coEvery { passkeyClient.startRegistration(token) } returns NetworkResult.Success(challengeResponse)

        val dummyAttestationJson = """
            {
                "id": "cred_id_registered",
                "rawId": "raw_id_registered",
                "response": {
                    "attestationObject": "attestation_bytes",
                    "clientDataJSON": "client_data_bytes"
                },
                "type": "public-key"
            }
        """.trimIndent()
        val dummyCredElement = json.parseToJsonElement(dummyAttestationJson)
        coEvery { internalAuthenticator.registerCredential(context, any()) } returns dummyCredElement

        val finishSlot = slot<PasskeyRegisterFinishRequest>()
        coEvery {
            passkeyClient.finishRegistration(capture(finishSlot), token)
        } returns NetworkResult.Success(Unit)

        val result = passkeyAuthManager.registerPasskey(context, "Pixel 8 Pro")
        assertTrue("Expected PasskeyRegisterResult.Success, got: $result", result is PasskeyRegisterResult.Success)

        assertTrue(finishSlot.isCaptured)
        val capturedReq = finishSlot.captured
        assertEquals("reg_challenge_uuid_123", capturedReq.challengeId)
        assertTrue(capturedReq.encryptedMetadata.isNotEmpty())

        // Verify that the encrypted metadata contains our device label
        val decryptedNickname = passkeyAuthManager.decryptMetadata(capturedReq.encryptedMetadata, masterKey)
        assertEquals("Pixel 8 Pro", decryptedNickname)
    }

    @Test
    fun `registerPasskey returns Cancelled when user cancels biometric prompt`() = runBlocking {
        every { sessionManager.getToken() } returns "active_token"
        val challengeResponse = createSampleRegistrationChallenge()
        coEvery { passkeyClient.startRegistration(any()) } returns NetworkResult.Success(challengeResponse)

        coEvery {
            internalAuthenticator.registerCredential(context, any())
        } throws BiometricCancelledException("User dismissed biometric dialog")

        val result = passkeyAuthManager.registerPasskey(context, "Pixel 8")
        assertEquals(PasskeyRegisterResult.Cancelled, result)
    }

    @Test
    fun `registerPasskey returns Error when authenticator throws exception`() = runBlocking {
        every { sessionManager.getToken() } returns "active_token"
        val challengeResponse = createSampleRegistrationChallenge()
        coEvery { passkeyClient.startRegistration(any()) } returns NetworkResult.Success(challengeResponse)

        coEvery {
            internalAuthenticator.registerCredential(context, any())
        } throws FidoAuthenticationException("Hardware error")

        val result = passkeyAuthManager.registerPasskey(context, "Pixel 8")
        assertTrue(result is PasskeyRegisterResult.Error)
        val error = result as PasskeyRegisterResult.Error
        assertTrue(error.message.contains("Hardware error"))
    }

    // --- Passkey Login Tests ---

    @Test
    fun `loginWithPasskey fails when startLogin returns NetworkResult Failure`() = runBlocking {
        coEvery { passkeyClient.startLogin() } returns NetworkResult.Connectivity.ConnectionRefused(
            port = 23342,
            userMessage = "Connection refused on port 23342"
        )

        val result = passkeyAuthManager.loginWithPasskey(context)
        assertTrue(result is PasskeyLoginResult.Error)
        val error = result as PasskeyLoginResult.Error
        assertTrue(error.message.contains("Connection refused"))
    }

    @Test
    fun `loginWithPasskey succeeds without passphrase saving token and reusing cached vault key`() = runBlocking {
        val cachedVaultKey = MasterVaultKey.generate()
        every { sessionManager.getVaultKey() } returns cachedVaultKey

        val challengeResponse = createSampleLoginChallenge()
        coEvery { passkeyClient.startLogin() } returns NetworkResult.Success(challengeResponse)

        val dummyAssertionJson = """
            {
                "id": "cred_id_login",
                "rawId": "raw_id_login",
                "response": {
                    "authenticatorData": "auth_data",
                    "clientDataJSON": "client_data",
                    "signature": "sig",
                    "userHandle": "handle"
                },
                "type": "public-key"
            }
        """.trimIndent()
        val dummyAssertionElement = json.parseToJsonElement(dummyAssertionJson)
        coEvery { internalAuthenticator.authenticateCredential(context, any()) } returns dummyAssertionElement

        val authResponse = AuthResponse(
            token = "new_bearer_session_token_118",
            wrappedVaultKey = "ab".repeat(60)
        )
        coEvery { passkeyClient.finishLogin(any()) } returns NetworkResult.Success(authResponse)

        val result = passkeyAuthManager.loginWithPasskey(context)
        assertTrue("Expected PasskeyLoginResult.Success, got: $result", result is PasskeyLoginResult.Success)
        val success = result as PasskeyLoginResult.Success
        assertEquals("new_bearer_session_token_118", success.token)
        assertEquals("ab".repeat(60), success.wrappedVaultKey)
        assertNotNull(success.masterVaultKey)
        assertArrayEquals(cachedVaultKey.keyBytes, success.masterVaultKey!!.keyBytes)

        coVerify(exactly = 1) { sessionManager.saveToken("new_bearer_session_token_118") }
    }

    @Test
    fun `loginWithPasskey succeeds with passphrase unwrapping vault key and saving to SessionManager`() = runBlocking {
        val masterKey = MasterVaultKey.generate()
        val passphrase = "correct horse battery staple"
        val dummyKek = ByteArray(32) { 0x42.toByte() }

        every { identityManager.deriveKek(passphrase) } returns dummyKek
        val wrappedVaultKeyHex = AesGcmEnvelope.wrap(masterKey, dummyKek)

        val challengeResponse = createSampleLoginChallenge()
        coEvery { passkeyClient.startLogin() } returns NetworkResult.Success(challengeResponse)

        val dummyAssertionJson = """
            {
                "id": "cred_id",
                "rawId": "raw_id",
                "response": {
                    "authenticatorData": "a",
                    "clientDataJSON": "b",
                    "signature": "c",
                    "userHandle": "d"
                },
                "type": "public-key"
            }
        """.trimIndent()
        val dummyAssertionElement = json.parseToJsonElement(dummyAssertionJson)
        coEvery { internalAuthenticator.authenticateCredential(context, any()) } returns dummyAssertionElement

        val authResponse = AuthResponse(
            token = "session_token_123",
            wrappedVaultKey = wrappedVaultKeyHex
        )
        coEvery { passkeyClient.finishLogin(any()) } returns NetworkResult.Success(authResponse)

        val savedKeySlot = slot<MasterVaultKey>()
        every { sessionManager.saveVaultKey(capture(savedKeySlot)) } returns Unit

        val result = passkeyAuthManager.loginWithPasskey(context, passphrase = passphrase)
        assertTrue("Expected PasskeyLoginResult.Success, got: $result", result is PasskeyLoginResult.Success)
        val success = result as PasskeyLoginResult.Success
        assertEquals("session_token_123", success.token)
        assertNotNull(success.masterVaultKey)
        assertArrayEquals(masterKey.keyBytes, success.masterVaultKey!!.keyBytes)

        coVerify(exactly = 1) { sessionManager.saveVaultKey(any()) }
    }

    @Test
    fun `loginWithPasskey returns Cancelled when user cancels biometric prompt`() = runBlocking {
        val challengeResponse = createSampleLoginChallenge()
        coEvery { passkeyClient.startLogin() } returns NetworkResult.Success(challengeResponse)

        coEvery {
            internalAuthenticator.authenticateCredential(context, any())
        } throws BiometricCancelledException("User cancelled biometric dialog")

        val result = passkeyAuthManager.loginWithPasskey(context)
        assertEquals(PasskeyLoginResult.Cancelled, result)
    }

    @Test
    fun `loginWithPasskey returns Error when authenticator throws exception`() = runBlocking {
        val challengeResponse = createSampleLoginChallenge()
        coEvery { passkeyClient.startLogin() } returns NetworkResult.Success(challengeResponse)

        coEvery {
            internalAuthenticator.authenticateCredential(context, any())
        } throws FidoAuthenticationException("Biometric sensor hardware failure")

        val result = passkeyAuthManager.loginWithPasskey(context)
        assertTrue(result is PasskeyLoginResult.Error)
        val error = result as PasskeyLoginResult.Error
        assertTrue(error.message.contains("Biometric sensor hardware failure"))
    }

    // --- Vault Unlocking & Credential Management Tests ---

    @Test
    fun `unlockVaultWithPassphrase succeeds and stores unwrapped MasterVaultKey`() {
        val masterKey = MasterVaultKey.generate()
        val passphrase = "my super secure password"
        val kek = ByteArray(32) { 0x33.toByte() }

        every { identityManager.deriveKek(passphrase) } returns kek
        val wrappedHex = AesGcmEnvelope.wrap(masterKey, kek)

        val savedKeySlot = slot<MasterVaultKey>()
        every { sessionManager.saveVaultKey(capture(savedKeySlot)) } returns Unit

        val unlocked = passkeyAuthManager.unlockVaultWithPassphrase(wrappedHex, passphrase)
        assertNotNull(unlocked)
        assertArrayEquals(masterKey.keyBytes, unlocked!!.keyBytes)
        coVerify(exactly = 1) { sessionManager.saveVaultKey(any()) }
    }

    @Test
    fun `unlockVaultWithPassphrase succeeds with whitespace-padded and mixed-case recovery phrase`() {
        val masterKey = MasterVaultKey.generate()
        val rawInput = "  Abandon   ABANDON  abandon   "
        val normalized = "abandon abandon abandon"
        val kek = ByteArray(32) { 0x44.toByte() }

        every { identityManager.normalizeRecoveryPhrase(rawInput) } returns normalized
        every { identityManager.deriveKek(normalized) } returns kek
        val wrappedHex = AesGcmEnvelope.wrap(masterKey, kek)

        val savedKeySlot = slot<MasterVaultKey>()
        every { sessionManager.saveVaultKey(capture(savedKeySlot)) } returns Unit

        val unlocked = passkeyAuthManager.unlockVaultWithPassphrase(wrappedHex, rawInput)
        assertNotNull(unlocked)
        assertArrayEquals(masterKey.keyBytes, unlocked!!.keyBytes)
        coVerify(exactly = 1) { sessionManager.saveVaultKey(any()) }
    }

    @Test
    fun `unlockVaultWithPassphrase returns null when unwrapping fails`() {
        val passphrase = "wrong password"
        val kek = ByteArray(32) { 0x55.toByte() }
        every { identityManager.deriveKek(passphrase) } returns kek

        val invalidWrappedHex = "00".repeat(60) // 120 hex chars of zeros
        val unlocked = passkeyAuthManager.unlockVaultWithPassphrase(invalidWrappedHex, passphrase)
        assertNull(unlocked)
    }

    @Test
    fun `listPasskeys delegates to PasskeyClient`() = runBlocking {
        val mockList = listOf(
            PasskeyMetadataRow("cred1", "meta1", 1730000000L, 0L),
            PasskeyMetadataRow("cred2", "meta2", 1730001000L, 1730002000L)
        )
        coEvery { passkeyClient.listPasskeys("tok") } returns NetworkResult.Success(mockList)

        val result = passkeyAuthManager.listPasskeys("tok")
        assertTrue(result is NetworkResult.Success)
        val list = (result as NetworkResult.Success).data
        assertEquals(2, list.size)
        assertEquals("cred1", list[0].credentialId)
    }

    @Test
    fun `deletePasskey delegates to PasskeyClient`() = runBlocking {
        coEvery { passkeyClient.deletePasskey("cred1", "tok") } returns NetworkResult.Success(Unit)

        val result = passkeyAuthManager.deletePasskey("cred1", "tok")
        assertTrue(result is NetworkResult.Success)
        coVerify(exactly = 1) { passkeyClient.deletePasskey("cred1", "tok") }
    }

    // --- Boundary & Edge Case Tests ---

    @Test
    fun `registerPasskey handles invalid challenge JSON gracefully without crashing`() = runBlocking {
        every { sessionManager.getToken() } returns "valid_token"
        // Malformed challenge that is not a valid JSON object with user.name
        val malformedChallenge = PasskeyChallengeResponse("bad_id", JsonPrimitive("not valid json"))
        coEvery { passkeyClient.startRegistration("valid_token") } returns NetworkResult.Success(malformedChallenge)

        val result = passkeyAuthManager.registerPasskey(context, "Pixel 8")
        assertTrue("Expected PasskeyRegisterResult.Error, got: $result", result is PasskeyRegisterResult.Error)
        val error = result as PasskeyRegisterResult.Error
        assertTrue(error.message.contains("Invalid registration challenge"))
    }

    @Test
    fun `loginWithPasskey handles invalid challenge JSON gracefully without crashing`() = runBlocking {
        val malformedChallenge = PasskeyChallengeResponse("bad_id", JsonPrimitive(""))
        coEvery { passkeyClient.startLogin() } returns NetworkResult.Success(malformedChallenge)

        val result = passkeyAuthManager.loginWithPasskey(context)
        assertTrue("Expected PasskeyLoginResult.Error, got: $result", result is PasskeyLoginResult.Error)
        val error = result as PasskeyLoginResult.Error
        assertTrue(error.message.contains("Invalid login challenge"))
    }

    @Test
    fun `unlockVaultWithPassphrase with blank inputs returns null without invoking deriveKek`() {
        assertNull(passkeyAuthManager.unlockVaultWithPassphrase("", "passphrase"))
        assertNull(passkeyAuthManager.unlockVaultWithPassphrase("   ", "passphrase"))
        assertNull(passkeyAuthManager.unlockVaultWithPassphrase("120hexchars", ""))
        assertNull(passkeyAuthManager.unlockVaultWithPassphrase("120hexchars", "   "))
        coVerify(exactly = 0) { identityManager.deriveKek(any()) }
    }

    @Test
    fun `decryptNickname with empty or whitespace credentialId returns Passkey without empty parentheses`() {
        val row = PasskeyMetadataRow(
            credentialId = "   ",
            encryptedMetadata = "",
            createdAt = 1730000000L,
            lastUsed = 0L
        )
        val resolved = passkeyAuthManager.decryptNickname(row, null)
        assertEquals("Passkey", resolved)
    }

    @Test
    fun `decryptMetadata with hex longer than 2048 returns null`() {
        val masterKey = MasterVaultKey.generate()
        val tooLongHex = "ab".repeat(1025) // 2050 hex chars
        assertNull(passkeyAuthManager.decryptMetadata(tooLongHex, masterKey))
    }

    @Test
    fun `decryptMetadata with non-hex characters returns null`() {
        val masterKey = MasterVaultKey.generate()
        val invalidHex = "gg".repeat(30) // valid length (60), but 'g' is not hex
        assertNull(passkeyAuthManager.decryptMetadata(invalidHex, masterKey))
    }

    @Test
    fun `registerPasskey sanitizes extremely long deviceNickname without failing metadata encryption`() = runBlocking {
        val token = "active_token"
        every { sessionManager.getToken() } returns token

        val masterKey = MasterVaultKey.generate()
        every { sessionManager.getVaultKey() } returns masterKey

        val challengeResponse = createSampleRegistrationChallenge()
        coEvery { passkeyClient.startRegistration(token) } returns NetworkResult.Success(challengeResponse)

        val dummyAttestationJson = """
            {
                "id": "cred_id_long",
                "rawId": "raw_id_long",
                "response": {
                    "attestationObject": "attestation_bytes",
                    "clientDataJSON": "client_data_bytes"
                },
                "type": "public-key"
            }
        """.trimIndent()
        val dummyCredElement = json.parseToJsonElement(dummyAttestationJson)
        coEvery { internalAuthenticator.registerCredential(context, any()) } returns dummyCredElement

        val finishSlot = slot<PasskeyRegisterFinishRequest>()
        coEvery {
            passkeyClient.finishRegistration(capture(finishSlot), token)
        } returns NetworkResult.Success(Unit)

        val veryLongNickname = "A".repeat(500)
        val result = passkeyAuthManager.registerPasskey(context, veryLongNickname)
        assertTrue(result is PasskeyRegisterResult.Success)

        assertTrue(finishSlot.isCaptured)
        val capturedMetadata = finishSlot.captured.encryptedMetadata
        assertTrue(capturedMetadata.isNotEmpty())
        assertTrue(capturedMetadata.length <= 2048)

        val decrypted = passkeyAuthManager.decryptMetadata(capturedMetadata, masterKey)
        assertNotNull(decrypted)
        assertEquals("A".repeat(200), decrypted)
    }

    // --- Mode 2 Registration Ticket Tests ---

    @Test
    fun `registerPasskeyWithTicket succeeds and provides ticket to finish endpoint`() = runBlocking {
        val testTicket = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val masterKey = MasterVaultKey.generate()
        every { sessionManager.getVaultKey() } returns masterKey

        val challengeResponse = createSampleRegistrationChallenge()
        coEvery { passkeyClient.startRegistrationWithTicket(testTicket) } returns NetworkResult.Success(challengeResponse)

        val dummyAttestationJson = """
            {
                "id": "cred_id_ticket",
                "rawId": "raw_id_ticket",
                "response": {
                    "attestationObject": "attestation_bytes",
                    "clientDataJSON": "client_data_bytes"
                },
                "type": "public-key"
            }
        """.trimIndent()
        val dummyCredElement = json.parseToJsonElement(dummyAttestationJson)
        coEvery { internalAuthenticator.registerCredential(context, any()) } returns dummyCredElement

        val finishSlot = slot<PasskeyRegisterFinishRequest>()
        coEvery {
            passkeyClient.finishRegistrationWithTicket(capture(finishSlot), testTicket)
        } returns NetworkResult.Success(Unit)

        val result = passkeyAuthManager.registerPasskeyWithTicket(
            activityContext = context,
            registrationTicket = testTicket,
            deviceNickname = "Paired Tablet"
        )

        assertTrue(result is PasskeyRegisterResult.Success)
        assertTrue(finishSlot.isCaptured)
        assertEquals(testTicket, finishSlot.captured.ticket)
        assertEquals("reg_challenge_uuid_123", finishSlot.captured.challengeId)

        val decryptedName = passkeyAuthManager.decryptMetadata(finishSlot.captured.encryptedMetadata, masterKey)
        assertEquals("Paired Tablet", decryptedName)

        coVerify(exactly = 1) { passkeyClient.startRegistrationWithTicket(testTicket) }
        coVerify(exactly = 1) { passkeyClient.finishRegistrationWithTicket(any(), testTicket) }
        masterKey.close()
    }

    @Test
    fun `registerPasskeyWithTicket returns Cancelled when biometric authentication is dismissed`() = runBlocking {
        val testTicket = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val challengeResponse = createSampleRegistrationChallenge()
        coEvery { passkeyClient.startRegistrationWithTicket(testTicket) } returns NetworkResult.Success(challengeResponse)
        coEvery { internalAuthenticator.registerCredential(context, any()) } throws BiometricCancelledException()

        val result = passkeyAuthManager.registerPasskeyWithTicket(
            activityContext = context,
            registrationTicket = testTicket,
            deviceNickname = "Paired Tablet"
        )

        assertTrue(result is PasskeyRegisterResult.Cancelled)
        coVerify(exactly = 0) { passkeyClient.finishRegistrationWithTicket(any(), any()) }
    }

    @Test
    fun `registerPasskeyWithTicket returns Error when start challenge fails`() = runBlocking {
        val testTicket = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        coEvery {
            passkeyClient.startRegistrationWithTicket(testTicket)
        } returns NetworkResult.fromHttp(404, "Registration ticket not found or expired")

        val result = passkeyAuthManager.registerPasskeyWithTicket(
            activityContext = context,
            registrationTicket = testTicket
        )

        assertTrue(result is PasskeyRegisterResult.Error)
        val error = result as PasskeyRegisterResult.Error
        assertTrue(error.message.contains("not found", ignoreCase = true))
        coVerify(exactly = 0) { internalAuthenticator.registerCredential(any(), any()) }
    }

    @Test
    fun `registerPasskeyWithTicket returns Error when finish registration fails`() = runBlocking {
        val testTicket = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val challengeResponse = createSampleRegistrationChallenge()
        coEvery { passkeyClient.startRegistrationWithTicket(testTicket) } returns NetworkResult.Success(challengeResponse)

        val dummyCredElement = json.parseToJsonElement("""{"id":"test","rawId":"test","type":"public-key"}""")
        coEvery { internalAuthenticator.registerCredential(context, any()) } returns dummyCredElement

        coEvery {
            passkeyClient.finishRegistrationWithTicket(any(), testTicket)
        } returns NetworkResult.fromHttp(400, "Invalid attestation signature")

        val result = passkeyAuthManager.registerPasskeyWithTicket(
            activityContext = context,
            registrationTicket = testTicket
        )

        assertTrue(result is PasskeyRegisterResult.Error)
        val error = result as PasskeyRegisterResult.Error
        assertTrue(error.message.contains("signature"))
    }
}
