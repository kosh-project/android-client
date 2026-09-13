package project.kosh.core.auth

import project.kosh.core.model.AuthCredential
import project.kosh.core.network.AuthClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authClient: AuthClient,
    private val sessionManager: SessionManager
) {

    fun register(credential: AuthCredential): Boolean {
        return try {
            authClient.register(credential)
        } catch (e: Exception) {
            false
        }
    }


    fun login(credential: AuthCredential): Boolean {
        return try {
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