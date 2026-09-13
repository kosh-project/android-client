package project.kosh.core.network

interface TokenProvider {
    fun getToken(): String?
}