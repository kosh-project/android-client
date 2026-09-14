package project.kosh.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import project.kosh.core.model.AuthCredential
import project.kosh.core.network.AuthClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authClient: AuthClient,
    private val sessionManager: SessionManager
) {

    suspend fun register(credential: AuthCredential): Boolean = withContext(Dispatchers.IO) {
        try {
            authClient.register(credential)
        } catch (e: Exception) {
            false
        }
    }


    suspend fun login(credential: AuthCredential): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = authClient.login(credential)
            if (token != null) {
                sessionManager.saveToken(token)
                true
            } else {
                false
            }
        } catch (e : Exception) {
            false
        }
    }

    fun isLoggedIn(): Boolean {
        return sessionManager.getToken() != null
    }
}