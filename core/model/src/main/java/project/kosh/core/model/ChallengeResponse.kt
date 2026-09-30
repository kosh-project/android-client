package project.kosh.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ChallengeResponse(
    val challenge: String
)
