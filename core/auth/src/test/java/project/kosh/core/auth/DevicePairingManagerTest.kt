package project.kosh.core.auth

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import project.kosh.core.crypto.EcdhPairingCrypto
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.model.MailboxAllocationResponse
import project.kosh.core.model.MailboxPollState
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PairingQrPayload
import project.kosh.core.network.MailboxClient
import project.kosh.core.network.ServerConfig
import project.kosh.core.network.ServerConfigurationStore

class DevicePairingManagerTest {

    private lateinit var mailboxClient: MailboxClient
    private lateinit var sessionManager: SessionManager
    private lateinit var serverConfigStore: ServerConfigurationStore
    private lateinit var devicePairingManager: DevicePairingManager
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        mailboxClient = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        serverConfigStore = mockk(relaxed = true)

        every { serverConfigStore.getServerConfig() } returns ServerConfig(
            host = "192.168.1.100",
            port = 6969,
            useTls = true,
            pinnedCertFingerprintSha256 = ByteArray(32) { 0xAA.toByte() }
        )

        devicePairingManager = DevicePairingManager(
            mailboxClient = mailboxClient,
            sessionManager = sessionManager,
            serverConfigStore = serverConfigStore
        )
    }

    @Test
    fun `fulfillSenderSession fails if no MasterVaultKey exists on device`() = runBlocking {
        every { sessionManager.getVaultKey() } returns null

        val result = devicePairingManager.fulfillSenderSession(
            mailboxId = "c7m9x4b2",
            receiverPublicKeyHex = "00".repeat(32)
        )

        assertTrue(result is NetworkResult.Failure)
        val failure = result as NetworkResult.Failure
        assertTrue(failure.userMessage.contains("No active Master Vault Key"))
    }

    @Test
    fun `fulfillSenderSession successfully encrypts 184-hex payload and submits to mailbox`() = runBlocking {
        val masterKey = MasterVaultKey.generate()
        every { sessionManager.getVaultKey() } returns masterKey

        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val rxPubHex = EcdhPairingCrypto.bytesToHex(receiverKeyPair.publicKey)

        val payloadSlot = slot<String>()
        coEvery {
            mailboxClient.fulfillMailbox("c7m9x4b2", capture(payloadSlot), any())
        } returns NetworkResult.Success(Unit)

        val result = devicePairingManager.fulfillSenderSession(
            mailboxId = "c7m9x4b2",
            receiverPublicKeyHex = rxPubHex,
            token = "session_token"
        )

        assertTrue(result is NetworkResult.Success)
        assertTrue(payloadSlot.isCaptured)
        val capturedPayload = payloadSlot.captured
        assertEquals(184, capturedPayload.length)

        // Decrypt with receiver key to verify integrity
        val recoveredKey = EcdhPairingCrypto.decryptPayload(capturedPayload, receiverKeyPair.privateKey)
        assertArrayEquals(masterKey.keyBytes, recoveredKey.keyBytes)
    }

    @Test
    fun `startReceiverFlow emits Ready, AwaitingFulfillment, and Success upon round-trip`() = runBlocking {
        val masterKeyToSend = MasterVaultKey.generate()

        // 1. Mock allocation
        coEvery { mailboxClient.allocateMailbox(any()) } returns NetworkResult.Success(
            MailboxAllocationResponse(mailboxId = "c7m9x4b2", expiresIn = 300L)
        )

        // 2. Mock polling flow
        var interceptedRxPubKeyHex = ""
        coEvery {
            mailboxClient.pollPickup("c7m9x4b2", any(), any(), any())
        } answers {
            // Simulate sender encrypting to receiver's public key
            val rxPubKey = EcdhPairingCrypto.hexToBytes(interceptedRxPubKeyHex)
            val senderEncryptedPayload = EcdhPairingCrypto.encryptPayload(masterKeyToSend, rxPubKey)

            flowOf(
                MailboxPollState.Pending,
                MailboxPollState.Fulfilled(senderEncryptedPayload)
            )
        }

        val savedKeySlot = slot<MasterVaultKey>()
        every { sessionManager.saveVaultKey(capture(savedKeySlot)) } returns Unit

        val states = mutableListOf<PairingState>()
        devicePairingManager.startReceiverFlow(token = "receiver_token").collect { state ->
            states.add(state)
            if (state is PairingState.Ready) {
                val parsedQr = json.decodeFromString<PairingQrPayload>(state.qrPayloadJson)
                interceptedRxPubKeyHex = parsedQr.publicKeyHex
                assertEquals("c7m9x4b2", parsedQr.mailboxId)
                assertEquals(64, parsedQr.publicKeyHex.length)
                assertEquals("192.168.1.100", parsedQr.host)
                assertEquals(6969, parsedQr.port)
            }
        }

        assertEquals(3, states.size)
        assertTrue(states[0] is PairingState.Ready)
        assertEquals(PairingState.AwaitingFulfillment, states[1])
        assertTrue(states[2] is PairingState.Success)

        val successState = states[2] as PairingState.Success
        assertArrayEquals(masterKeyToSend.keyBytes, successState.vaultKey.keyBytes)
        coVerify(exactly = 1) { sessionManager.saveVaultKey(any()) }
    }
}
