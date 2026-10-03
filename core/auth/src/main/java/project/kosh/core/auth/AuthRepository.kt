package project.kosh.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import project.kosh.core.crypto.HashcashSolver
import project.kosh.core.model.AuthCredential
import project.kosh.core.model.AuthRequest
import project.kosh.core.model.NetworkResult
import project.kosh.core.network.AuthClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authClient: AuthClient,
    private val sessionManager: SessionManager,
    private val hashcashSolver: HashcashSolver
) {

    suspend fun register(credential: AuthCredential): NetworkResult<Unit> =
        withContext(Dispatchers.IO) {
            val challenge = when (val challengeResult = authClient.fetchChallenge("register")) {
                is NetworkResult.Success -> challengeResult.data.challenge
                is NetworkResult.Failure -> return@withContext challengeResult
            }

            val hashcashHeader = hashcashSolver.solve(credential.identityHash, challenge)

            val requestDto = AuthRequest(authVerifier = credential.authVerifier)
            authClient.register(requestDto, hashcashHeader)
        }


    suspend fun login(credential: AuthCredential): NetworkResult<String> =
        withContext(Dispatchers.IO) {
            val challengeResult = authClient.fetchChallenge("login")
            val challenge = when (challengeResult) {
                is NetworkResult.Success -> challengeResult.data.challenge
                is NetworkResult.Failure -> return@withContext challengeResult
            }

            val hashcashHeader = hashcashSolver.solve(credential.identityHash, challenge)

            val requestDto = AuthRequest(authVerifier = credential.authVerifier)
            val loginResult = authClient.login(requestDto, hashcashHeader)

            if (loginResult is NetworkResult.Success) {
                sessionManager.saveToken(loginResult.data)
            }

            loginResult
        }

    fun logout() {
        sessionManager.clearToken()
    }

    fun isLoggedIn(): Boolean {
        return sessionManager.getToken() != null
    }
}