package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Internal domain model representing cryptographically derived user credentials.
 *
 * In Kosh's zero-knowledge security model, a user's plaintext password is never sent over
 * the wire or stored in memory longer than necessary for key derivation. Instead, the client
 * derives two distinct artifacts using [IdentityManager]:
 * 1. An [identityHash] that uniquely identifies the account without exposing the raw username.
 * 2. An [authVerifier] computed via Argon2id that allows the server to authenticate the client
 *    without having knowledge of the master password.
 *
 * Note on wire protocol differences:
 * While this internal data class holds both fields, the wire-level request payload ([AuthRequest])
 * transmits only the [authVerifier]. The server's Proof-of-Work guard (`pow_guard`) extracts the
 * [identityHash] directly from the `X-Hashcash` HTTP header, eliminating redundant data on the wire.
 *
 * @property identityHash A 64-character lowercase hexadecimal string produced by SHA-256 hashing
 *   the UTF-8 username bytes. Used as the public account identifier and Hashcash subject.
 * @property authVerifier An encoded Argon2id string derived from the password and salt. The server
 *   stores and verifies this verifier verbatim without re-hashing, keeping server CPU overhead minimal.
 */
@Serializable
data class AuthCredential(
    @SerialName("identity_hash")
    val identityHash: String,
    @SerialName("auth_verifier")
    val authVerifier: String,
)