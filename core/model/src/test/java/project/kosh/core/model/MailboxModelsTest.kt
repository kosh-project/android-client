package project.kosh.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun mailboxOpenRequest_enforces64HexLength() {
        val validPubTemp = "ab".repeat(32)
        val request = MailboxOpenRequest(validPubTemp)
        assertEquals(validPubTemp, request.pubTemp)

        assertThrows(IllegalArgumentException::class.java) {
            MailboxOpenRequest("too_short")
        }

        assertThrows(IllegalArgumentException::class.java) {
            MailboxOpenRequest("ab".repeat(33))
        }
    }

    @Test
    fun mailboxOpenResponse_serializesAndDeserializes() {
        val jsonString = """
            {
                "mailbox_id": "c7m9x4b2",
                "polling_token": "a1b2c3d4e5f60123456789abcdef0123456789abcdef0123456789abcdef0123",
                "expires_in": 300
            }
        """.trimIndent()

        val response = json.decodeFromString<MailboxOpenResponse>(jsonString)
        assertEquals("c7m9x4b2", response.mailboxId)
        assertEquals(300L, response.expiresIn)
        assertTrue(response.pollingToken.startsWith("a1b2c3d4"))

        val serialized = json.encodeToString(MailboxOpenResponse.serializer(), response)
        assertTrue(serialized.contains("c7m9x4b2"))
    }

    @Test
    fun mailboxJoinRequest_enforces64HexLength() {
        val validPubTemp = "cd".repeat(32)
        val request = MailboxJoinRequest(validPubTemp)
        assertEquals(validPubTemp, request.pubTemp)

        assertThrows(IllegalArgumentException::class.java) {
            MailboxJoinRequest("")
        }

        assertThrows(IllegalArgumentException::class.java) {
            MailboxJoinRequest("cd".repeat(31))
        }
    }

    @Test
    fun mailboxJoinResponse_serializesAndDeserializes() {
        val jsonString = """{"token": "join_token_123456"}"""
        val response = json.decodeFromString<MailboxJoinResponse>(jsonString)
        assertEquals("join_token_123456", response.token)
    }

    @Test
    fun mailboxStatusResponse_computesWaitingAndReadyFlags() {
        val waitingResponse = MailboxStatusResponse(status = "waiting")
        assertTrue(waitingResponse.isWaiting)
        assertFalse(waitingResponse.isReady)

        val readyResponse = MailboxStatusResponse(status = "ready", pubTemp = "ab".repeat(32))
        assertFalse(readyResponse.isWaiting)
        assertTrue(readyResponse.isReady)

        val readyWithoutKey = MailboxStatusResponse(status = "ready", pubTemp = null)
        assertFalse(readyWithoutKey.isReady)

        val uppercaseStatus = MailboxStatusResponse(status = "READY", pubTemp = "ab".repeat(32))
        assertTrue(uppercaseStatus.isReady)
    }

    @Test
    fun mailboxAllocationResponse_serializesAndDeserializes() {
        val jsonString = """{"mailbox_id": "k8n2p4x9", "expires_in": 300}"""
        val response = json.decodeFromString<MailboxAllocationResponse>(jsonString)
        assertEquals("k8n2p4x9", response.mailboxId)
        assertEquals(300L, response.expiresIn)
    }

    @Test
    fun mailboxFulfillRequest_enforcesLengthBounds() {
        val minPayload = "00".repeat(60) // 120 hex chars
        val validRequest = MailboxFulfillRequest(minPayload)
        assertEquals(120, validRequest.payload.length)

        val maxPayload = "00".repeat(4096) // 8192 hex chars
        val validMaxRequest = MailboxFulfillRequest(maxPayload)
        assertEquals(8192, validMaxRequest.payload.length)

        assertThrows(IllegalArgumentException::class.java) {
            MailboxFulfillRequest("00".repeat(59)) // 118 hex chars
        }

        assertThrows(IllegalArgumentException::class.java) {
            MailboxFulfillRequest("00".repeat(4097)) // 8194 hex chars
        }
    }

    @Test
    fun mailboxPickupResponse_evaluatesPendingAndFulfilledFlags() {
        val pending = MailboxPickupResponse(status = "pending")
        assertTrue(pending.isPending)
        assertFalse(pending.isFulfilled)

        val fulfilled = MailboxPickupResponse(status = "fulfilled", payload = "ab".repeat(62))
        assertFalse(fulfilled.isPending)
        assertTrue(fulfilled.isFulfilled)

        val fulfilledEmptyPayload = MailboxPickupResponse(status = "fulfilled", payload = "")
        assertFalse(fulfilledEmptyPayload.isFulfilled)
    }

    @Test
    fun linkTicketResponse_handlesBothTicketKeysAndExposesEffectiveTicket() {
        val jsonBoth = """
            {
                "ticket": "ticket_value_123",
                "registration_ticket": "ticket_value_123",
                "expires_in": 300
            }
        """.trimIndent()
        val parsedBoth = json.decodeFromString<LinkTicketResponse>(jsonBoth)
        assertEquals("ticket_value_123", parsedBoth.ticket)
        assertEquals("ticket_value_123", parsedBoth.registrationTicket)
        assertEquals("ticket_value_123", parsedBoth.effectiveTicket)

        val jsonTicketOnly = """{"ticket": "only_ticket_value"}"""
        val parsedTicketOnly = json.decodeFromString<LinkTicketResponse>(jsonTicketOnly)
        assertEquals("only_ticket_value", parsedTicketOnly.effectiveTicket)

        val jsonRegOnly = """{"registration_ticket": "only_reg_value"}"""
        val parsedRegOnly = json.decodeFromString<LinkTicketResponse>(jsonRegOnly)
        assertEquals("only_reg_value", parsedRegOnly.effectiveTicket)
    }

    @Test
    fun pairingQrPayload_serializesAndDeserializesCompletely() {
        val payload = PairingQrPayload(
            mailboxId = "c7m9x4b2",
            publicKeyHex = "aa".repeat(32),
            host = "192.168.1.150",
            port = 23342,
            fingerprint = "0123456789abcdef".repeat(4),
            token = "peer_token",
            mode = "receiver"
        )

        val serialized = json.encodeToString(PairingQrPayload.serializer(), payload)
        val deserialized = json.decodeFromString<PairingQrPayload>(serialized)

        assertEquals("c7m9x4b2", deserialized.mailboxId)
        assertEquals("aa".repeat(32), deserialized.publicKeyHex)
        assertEquals("192.168.1.150", deserialized.host)
        assertEquals(23342, deserialized.port)
        assertNotNull(deserialized.fingerprint)
        assertEquals("receiver", deserialized.mode)
    }
}
