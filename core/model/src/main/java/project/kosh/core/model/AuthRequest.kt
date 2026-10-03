package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-level JSON request payload transmitted to the Kosh authentication endpoints
 * (`POST /api/auth/register` and `POST /api/auth/login`).
 *
 * Notice that this payload contains only the [authVerifier] field and intentionally omits
 * the user's identity hash. The backend server's Proof-of-Work inspection layer (`pow_guard`)
 * parses and validates the `X-Hashcash` HTTP header before routing to the authentication
 * handlers. The server extracts the `identity_hash` directly from byte offsets `0..64` of the
 * header, keeping the JSON request body lean and non-redundant.
 *
 * @property authVerifier The Argon2id password verifier string formatted according to the standard
 *   Argon2 encoded string format (`$argon2id$v=19$m=65536,t=3,p=1$...`).
 */
@Serializable
data class AuthRequest(
    @SerialName("auth_verifier")
    val authVerifier: String
)
