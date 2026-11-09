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
import project.kosh.core.crypto.HashcashSolver
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.model.ChallengeResponse
import project.kosh.core.model.LinkTicketResponse
import project.kosh.core.model.MailboxAllocationResponse
import project.kosh.core.model.MailboxJoinResponse
import project.kosh.core.model.MailboxOpenResponse
import project.kosh.core.model.MailboxPickupResponse
import project.kosh.core.model.MailboxPollState
import project.kosh.core.model.MailboxStatusResponse
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PairingQrPayload
import project.kosh.core.network.AuthClient
import project.kosh.core.network.MailboxClient
import project.kosh.core.network.ServerConfig
import project.kosh.core.network.ServerConfigurationStore

class DevicePairingManagerTest {

    private lateinit var mailboxClient: MailboxClient
    private lateinit var authClient: AuthClient
    private lateinit var hashcashSolver: HashcashSolver
    private lateinit var sessionManager: SessionManager
    private lateinit var serverConfigStore: ServerConfigurationStore
    private lateinit var devicePairingManager: DevicePairingManager
    private val json = Json { ignoreUnknownKeys = true }

    private val testTicket = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        mailboxClient = mockk(relaxed = true)
        authClient = mockk(relaxed = true)
        hashcashSolver = mockk(relaxed = true)
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
            authClient = authClient,
            hashcashSolver = hashcashSolver,
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
    fun `fulfillSenderSession mints registration ticket and deposits 248-hex compound payload`() = runBlocking {
        val masterKey = MasterVaultKey.generate()
        every { sessionManager.getVaultKey() } returns masterKey

        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val rxPubHex = EcdhPairingCrypto.bytesToHex(receiverKeyPair.publicKey)

        coEvery { mailboxClient.mintLinkTicket(any()) } returns NetworkResult.Success(
            LinkTicketResponse(
                ticket = testTicket,
                registrationTicket = testTicket,
                expiresIn = 300L
            )
        )

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
        assertEquals(248, capturedPayload.length)

        // Decrypt with receiver key to verify integrity of both ticket and vault key
        val bundle = EcdhPairingCrypto.decryptCompoundPayload(capturedPayload, receiverKeyPair.privateKey)
        assertEquals(testTicket, bundle.registrationTicket)
        assertArrayEquals(masterKey.keyBytes, bundle.vaultKey.keyBytes)

        bundle.close()
        receiverKeyPair.close()
    }

    @Test
    fun `startReceiverFlow solves PoW, opens mailbox, and emits Success upon pickup`() = runBlocking {
        val masterKeyToSend = MasterVaultKey.generate()

        // 1. Mock challenge and PoW solver
        coEvery { authClient.fetchChallenge("register") } returns NetworkResult.Success(
            ChallengeResponse("mock_challenge_string")
        )
        coEvery { hashcashSolver.solve(any(), any()) } returns "mock_pow_header_string"

        // 2. Mock mailbox open
        coEvery {
            mailboxClient.openMailbox(any(), "mock_pow_header_string")
        } returns NetworkResult.Success(
            MailboxOpenResponse(
                mailboxId = "c7m9x4b2",
                pollingToken = "mock_polling_token_64_chars",
                expiresIn = 300L
            )
        )

        // 3. Mock polling flow
        var interceptedRxPubKeyHex = ""
        coEvery {
            mailboxClient.pollPickup("c7m9x4b2", "mock_polling_token_64_chars", any(), any())
        } answers {
            val rxPubKey = EcdhPairingCrypto.hexToBytes(interceptedRxPubKeyHex)
            val senderEncryptedPayload = EcdhPairingCrypto.encryptCompoundPayload(
                ticketHex = testTicket,
                vaultKey = masterKeyToSend,
                peerPublicKey = rxPubKey
            )

            flowOf(
                MailboxPollState.Pending,
                MailboxPollState.Fulfilled(senderEncryptedPayload)
            )
        }

        val savedKeySlot = slot<MasterVaultKey>()
        every { sessionManager.saveVaultKey(capture(savedKeySlot)) } returns Unit

        val states = mutableListOf<PairingState>()
        devicePairingManager.startReceiverFlow().collect { state ->
            states.add(state)
            if (state is PairingState.Ready) {
                val parsedQr = json.decodeFromString<PairingQrPayload>(state.qrPayloadJson)
                interceptedRxPubKeyHex = parsedQr.publicKeyHex!!
                assertEquals("c7m9x4b2", parsedQr.mailboxId)
                assertEquals(64, parsedQr.publicKeyHex?.length)
                assertEquals("192.168.1.100", parsedQr.host)
                assertEquals(6969, parsedQr.port)
                assertEquals("receiver", parsedQr.mode)
            }
        }

        assertEquals(3, states.size)
        assertTrue(states[0] is PairingState.Ready)
        assertEquals(PairingState.AwaitingFulfillment, states[1])
        assertTrue(states[2] is PairingState.Success)

        val successState = states[2] as PairingState.Success
        assertArrayEquals(masterKeyToSend.keyBytes, successState.vaultKey.keyBytes)
        assertEquals(testTicket, successState.registrationTicket)
        coVerify(exactly = 1) { sessionManager.saveVaultKey(any()) }
        coVerify(exactly = 1) { sessionManager.saveRegistrationTicket(testTicket) }
    }

    @Test
    fun `startHostFlow creates offered mailbox, polls status, and fulfills upon receiver join`() = runBlocking {
        val masterKey = MasterVaultKey.generate()
        every { sessionManager.getToken() } returns "active-token"
        every { sessionManager.getVaultKey() } returns masterKey

        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val rxPubHex = EcdhPairingCrypto.bytesToHex(receiverKeyPair.publicKey)

        coEvery { mailboxClient.allocateMailbox("active-token") } returns NetworkResult.Success(
            MailboxAllocationResponse(mailboxId = "k8n2p4x9", expiresIn = 300L)
        )
        coEvery { mailboxClient.mintLinkTicket("active-token") } returns NetworkResult.Success(
            LinkTicketResponse(ticket = testTicket, registrationTicket = testTicket, expiresIn = 300L)
        )
        coEvery { mailboxClient.checkStatus("k8n2p4x9", "active-token") } returns NetworkResult.Success(
            MailboxStatusResponse(status = "ready", pubTemp = rxPubHex)
        )
        coEvery { mailboxClient.fulfillMailbox("k8n2p4x9", any(), "active-token") } returns NetworkResult.Success(Unit)

        val states = devicePairingManager.startHostFlow().toList()
        assertEquals(2, states.size)
        assertTrue(states[0] is PairingState.AwaitingJoin)
        assertTrue(states[1] is PairingState.Success)

        val awaitingJoin = states[0] as PairingState.AwaitingJoin
        assertEquals("k8n2p4x9", awaitingJoin.mailboxId)
        val parsedQr = json.decodeFromString<PairingQrPayload>(awaitingJoin.qrPayloadJson)
        assertEquals("sender", parsedQr.mode)

        receiverKeyPair.close()
    }

    @Test
    fun `startHostFlow retries transient status errors before succeeding`() = runBlocking {
        val masterKey = MasterVaultKey.generate()
        every { sessionManager.getToken() } returns "active-token"
        every { sessionManager.getVaultKey() } returns masterKey

        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val rxPubHex = EcdhPairingCrypto.bytesToHex(receiverKeyPair.publicKey)

        coEvery { mailboxClient.allocateMailbox("active-token") } returns NetworkResult.Success(
            MailboxAllocationResponse(mailboxId = "k8n2p4x9", expiresIn = 300L)
        )
        coEvery { mailboxClient.mintLinkTicket("active-token") } returns NetworkResult.Success(
            LinkTicketResponse(ticket = testTicket, registrationTicket = testTicket, expiresIn = 300L)
        )

        var callCount = 0
        coEvery { mailboxClient.checkStatus("k8n2p4x9", "active-token") } answers {
            callCount++
            if (callCount == 1) {
                NetworkResult.fromHttp(500, "Internal Server Error")
            } else {
                NetworkResult.Success(MailboxStatusResponse(status = "ready", pubTemp = rxPubHex))
            }
        }
        coEvery { mailboxClient.fulfillMailbox("k8n2p4x9", any(), "active-token") } returns NetworkResult.Success(Unit)

        val states = devicePairingManager.startHostFlow().toList()
        assertEquals(2, states.size)
        assertTrue(states[0] is PairingState.AwaitingJoin)
        assertTrue(states[1] is PairingState.Success)

        receiverKeyPair.close()
        masterKey.close()
    }

    @Test
    fun `startJoinFlow solves PoW, joins mailbox, and recovers compound bundle`() = runBlocking {
        val masterKey = MasterVaultKey.generate()

        coEvery { authClient.fetchChallenge("register") } returns NetworkResult.Success(
            ChallengeResponse("join_challenge")
        )
        coEvery { hashcashSolver.solve(any(), any()) } returns "join_pow_header"

        var interceptedRxPubKeyHex = ""
        coEvery {
            mailboxClient.joinMailbox("k8n2p4x9", any(), "join_pow_header")
        } answers {
            interceptedRxPubKeyHex = secondArg()
            NetworkResult.Success(MailboxJoinResponse("join_polling_token"))
        }

        coEvery {
            mailboxClient.pollPickup("k8n2p4x9", "join_polling_token", any(), any())
        } answers {
            val rxPubKey = EcdhPairingCrypto.hexToBytes(interceptedRxPubKeyHex)
            val payload = EcdhPairingCrypto.encryptCompoundPayload(testTicket, masterKey, rxPubKey)
            flowOf(MailboxPollState.Fulfilled(payload))
        }

        val states = devicePairingManager.startJoinFlow("k8n2p4x9").toList()
        assertEquals(2, states.size)
        assertEquals(PairingState.AwaitingFulfillment, states[0])
        assertTrue(states[1] is PairingState.Success)

        val success = states[1] as PairingState.Success
        assertArrayEquals(masterKey.keyBytes, success.vaultKey.keyBytes)
        assertEquals(testTicket, success.registrationTicket)
        coVerify(exactly = 1) { sessionManager.saveVaultKey(any()) }
        coVerify(exactly = 1) { sessionManager.saveRegistrationTicket(testTicket) }
        masterKey.close()
    }

    @Test
    fun `startJoinFlow pins TLS certificate when fingerprint provided`() = runBlocking {
        val masterKey = MasterVaultKey.generate()
        val dummyFingerprint = "aa".repeat(32)

        coEvery { authClient.fetchChallenge("register") } returns NetworkResult.Success(
            ChallengeResponse("join_challenge")
        )
        coEvery { hashcashSolver.solve(any(), any()) } returns "join_pow_header"
        coEvery {
            mailboxClient.joinMailbox(any(), any(), any())
        } returns NetworkResult.Success(MailboxJoinResponse("join_polling_token"))

        coEvery {
            mailboxClient.pollPickup(any(), any(), any(), any())
        } answers {
            val rxPubKey = ByteArray(32) { 0x01 }
            val payload = EcdhPairingCrypto.encryptCompoundPayload(testTicket, masterKey, rxPubKey)
            flowOf(MailboxPollState.Fulfilled(payload))
        }

        devicePairingManager.startJoinFlow(
            mailboxId = "k8n2p4x9",
            host = "192.168.1.150",
            port = 23342,
            fingerprint = dummyFingerprint
        ).toList()

        coVerify(exactly = 1) {
            serverConfigStore.updateServer(
                host = "192.168.1.150",
                port = 23342,
                useTls = true,
                pinnedCertFingerprintSha256 = any()
            )
        }
        masterKey.close()
    }

    @Test
    fun `receivePreFulfilledVaultKey unwraps payload and saves key`() = runBlocking {
        val masterKey = MasterVaultKey.generate()
        val transferKey = ByteArray(32) { 0x55.toByte() }
        val payloadHex = project.kosh.core.crypto.AesGcmEnvelope.wrap(masterKey, transferKey)
        val transferKeyHex = EcdhPairingCrypto.bytesToHex(transferKey)

        coEvery {
            mailboxClient.pickupMailbox("c7m9x4b2", "peer-token")
        } returns NetworkResult.Success(
            MailboxPickupResponse(status = "fulfilled", payload = payloadHex)
        )

        val savedKeySlot = slot<MasterVaultKey>()
        every { sessionManager.saveVaultKey(capture(savedKeySlot)) } returns Unit

        val result = devicePairingManager.receivePreFulfilledVaultKey(
            mailboxId = "c7m9x4b2",
            transferKeyHex = transferKeyHex,
            token = "peer-token"
        )

        assertTrue(result is NetworkResult.Success)
        val unwrappedKey = (result as NetworkResult.Success).data
        assertArrayEquals(masterKey.keyBytes, unwrappedKey.keyBytes)
        coVerify(exactly = 1) { sessionManager.saveVaultKey(any()) }
    }

    @Test
    fun `startReceiverFlow emits Error when payload decryption fails`() = runBlocking {
        coEvery { authClient.fetchChallenge("register") } returns NetworkResult.Success(
            ChallengeResponse("mock_challenge")
        )
        coEvery { hashcashSolver.solve(any(), any()) } returns "mock_pow"
        coEvery { mailboxClient.openMailbox(any(), any()) } returns NetworkResult.Success(
            MailboxOpenResponse(mailboxId = "c7m9x4b2", pollingToken = "token", expiresIn = 300L)
        )
        coEvery { mailboxClient.pollPickup(any(), any(), any(), any()) } returns flowOf(
            MailboxPollState.Fulfilled("deadbeef".repeat(31))
        )

        val states = devicePairingManager.startReceiverFlow().toList()
        assertTrue(states.last() is PairingState.Error)
        val error = states.last() as PairingState.Error
        assertTrue(error.message.contains("Failed to decrypt transferred pairing bundle"))
    }

    @Test
    fun `fulfillSenderWithCode fulfills successfully when peer is ready`() = runBlocking {
        val masterKey = MasterVaultKey.generate()
        every { sessionManager.getToken() } returns "active-token"
        every { sessionManager.getVaultKey() } returns masterKey

        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val rxPubHex = EcdhPairingCrypto.bytesToHex(receiverKeyPair.publicKey)

        coEvery { mailboxClient.checkStatus("c7m9x4b2", "active-token") } returns NetworkResult.Success(
            MailboxStatusResponse(status = "ready", pubTemp = rxPubHex)
        )
        coEvery { mailboxClient.mintLinkTicket("active-token") } returns NetworkResult.Success(
            LinkTicketResponse(ticket = testTicket, registrationTicket = testTicket, expiresIn = 300L)
        )
        coEvery { mailboxClient.fulfillMailbox("c7m9x4b2", any(), "active-token") } returns NetworkResult.Success(Unit)

        val result = devicePairingManager.fulfillSenderWithCode("c7m9-x4b2", "active-token")
        assertTrue(result is NetworkResult.Success)

        receiverKeyPair.close()
        masterKey.close()
    }

    @Test
    fun `fulfillSenderWithCode retries when peer initially waiting and succeeds when ready`() = runBlocking {
        val masterKey = MasterVaultKey.generate()
        every { sessionManager.getToken() } returns "active-token"
        every { sessionManager.getVaultKey() } returns masterKey

        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val rxPubHex = EcdhPairingCrypto.bytesToHex(receiverKeyPair.publicKey)

        var attempts = 0
        coEvery { mailboxClient.checkStatus("c7m9x4b2", "active-token") } answers {
            attempts++
            if (attempts == 1) {
                NetworkResult.Success(MailboxStatusResponse(status = "waiting", pubTemp = null))
            } else {
                NetworkResult.Success(MailboxStatusResponse(status = "ready", pubTemp = rxPubHex))
            }
        }
        coEvery { mailboxClient.mintLinkTicket("active-token") } returns NetworkResult.Success(
            LinkTicketResponse(ticket = testTicket, registrationTicket = testTicket, expiresIn = 300L)
        )
        coEvery { mailboxClient.fulfillMailbox("c7m9x4b2", any(), "active-token") } returns NetworkResult.Success(Unit)

        val result = devicePairingManager.fulfillSenderWithCode(
            mailboxId = "c7m9-x4b2",
            token = "active-token",
            maxRetries = 3,
            retryDelayMillis = 10L
        )
        assertTrue(result is NetworkResult.Success)
        assertEquals(2, attempts)

        receiverKeyPair.close()
        masterKey.close()
    }

    @Test
    fun `fulfillSenderWithCode fails immediately on invalid code length`() = runBlocking {
        val result = devicePairingManager.fulfillSenderWithCode("short")
        assertTrue(result is NetworkResult.Failure)
        assertTrue((result as NetworkResult.Failure).userMessage.contains("must be 8 characters"))
    }
}
