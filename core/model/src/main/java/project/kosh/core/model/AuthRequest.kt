package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthRequest(
    @SerialName("auth_verifier")
    val authVerifier: String
)
