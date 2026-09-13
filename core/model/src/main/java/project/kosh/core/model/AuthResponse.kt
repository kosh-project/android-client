package project.kosh.core.model

import kotlinx.serialization.Serializable


@Serializable
data class AuthResponse(
    val token: String,
)
