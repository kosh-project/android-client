package project.kosh.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun passkeyChallengeResponse_extractsPublicKeyJsonFromNestedObject() {
        val challengeJson = buildJsonObject {
            put("publicKey", buildJsonObject {
                put("challenge", "mock_challenge_bytes")
                put("rpId", "kosh.local")
                put("timeout", 60000)
            })
        }
        val response = PasskeyChallengeResponse("challenge_id_123", challengeJson)
        val extracted = response.publicKeyJson
        assertTrue(extracted.contains("mock_challenge_bytes"))
        assertTrue(extracted.contains("kosh.local"))
        assertFalse(extracted.contains("\"publicKey\":{"))
    }

    @Test
    fun passkeyChallengeResponse_handlesDirectFlatObject() {
        val flatJson = buildJsonObject {
            put("challenge", "direct_challenge")
            put("rpId", "kosh.local")
        }
        val response = PasskeyChallengeResponse("challenge_id_456", flatJson)
        val extracted = response.publicKeyJson
        assertTrue(extracted.contains("direct_challenge"))
        assertTrue(extracted.contains("kosh.local"))
    }

    @Test
    fun passkeyChallengeResponse_handlesJsonPrimitiveString() {
        val primitiveJson = JsonPrimitive("""{"publicKey":{"challenge":"string_wrapped"}}""")
        val response = PasskeyChallengeResponse("challenge_id_789", primitiveJson)
        val extracted = response.publicKeyJson
        assertTrue(extracted.contains("string_wrapped"))
    }

    @Test
    fun passkeyRegisterFinishRequest_serializesAndDeserializes() {
        val dummyCredential = buildJsonObject {
            put("id", "cred_id")
            put("type", "public-key")
        }
        val request = PasskeyRegisterFinishRequest(
            challengeId = "challenge_uuid_abc",
            credential = dummyCredential,
            encryptedMetadata = "deadbeefcafebabe",
            ticket = "registration_ticket_123"
        )

        val serialized = json.encodeToString(PasskeyRegisterFinishRequest.serializer(), request)
        assertTrue(serialized.contains("challenge_uuid_abc"))
        assertTrue(serialized.contains("deadbeefcafebabe"))
        assertTrue(serialized.contains("registration_ticket_123"))

        val deserialized = json.decodeFromString<PasskeyRegisterFinishRequest>(serialized)
        assertEquals("challenge_uuid_abc", deserialized.challengeId)
        assertEquals("deadbeefcafebabe", deserialized.encryptedMetadata)
        assertEquals("registration_ticket_123", deserialized.ticket)
    }

    @Test
    fun passkeyLoginFinishRequest_serializesAndDeserializes() {
        val dummyCredential = buildJsonObject {
            put("id", "login_cred_id")
            put("type", "public-key")
        }
        val request = PasskeyLoginFinishRequest(
            challengeId = "challenge_uuid_xyz",
            credential = dummyCredential
        )

        val serialized = json.encodeToString(PasskeyLoginFinishRequest.serializer(), request)
        assertTrue(serialized.contains("challenge_uuid_xyz"))

        val deserialized = json.decodeFromString<PasskeyLoginFinishRequest>(serialized)
        assertEquals("challenge_uuid_xyz", deserialized.challengeId)
    }

    @Test
    fun passkeyMetadataRow_evaluatesHasBeenUsedFlag() {
        val unusedRow = PasskeyMetadataRow(
            credentialId = "cred_unused",
            encryptedMetadata = "",
            createdAt = 1730000000L,
            lastUsed = 0L
        )
        assertFalse(unusedRow.hasBeenUsed)

        val usedRow = PasskeyMetadataRow(
            credentialId = "cred_used",
            encryptedMetadata = "616263",
            createdAt = 1730000000L,
            lastUsed = 1730005000L
        )
        assertTrue(usedRow.hasBeenUsed)
    }
}
