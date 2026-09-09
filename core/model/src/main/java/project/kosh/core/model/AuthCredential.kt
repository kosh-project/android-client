package project.kosh.core.model

data class AuthCredential(
    val identityHash: String,
    val authVerifier: String,
)