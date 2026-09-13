package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthCredential(
    @SerialName("identity_hash")
    val identityHash: String,
    @SerialName("auth_verifier")
    val authVerifier: String,
)