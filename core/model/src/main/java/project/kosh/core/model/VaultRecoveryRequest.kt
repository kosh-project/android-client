package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VaultRecoveryRequest (
    @SerialName("identity_hash")
    val identityHash: String,

    @SerialName("auth_verifier")
    val authVerifier: String,

    @SerialName("wrapped_vault_key")
    val wrappedVaultKey: String,

    @SerialName("revoke_other_sessions")
    val revokeOtherSessions: Boolean = false
) {
}