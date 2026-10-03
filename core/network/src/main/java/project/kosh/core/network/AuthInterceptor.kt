package project.kosh.core.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp [Interceptor] responsible for injecting bearer authentication credentials into outgoing HTTP requests.
 *
 * This interceptor is wired exclusively into the authenticated OkHttpClient ([AuthOkHttpClient]).
 * Unauthenticated endpoints (such as `/api/auth/challenge`, `/api/auth/login`, and `/api/auth/register`)
 * use [PublicOkHttpClient] and bypass this interceptor to avoid transmitting invalid or stale tokens.
 *
 * When an active session exists, this interceptor appends the structured 118-byte bearer token
 * via the standard `Authorization: Bearer <token>` header, satisfying the server's stateless
 * BLAKE3 MAC verification filter (`mac_guard`).
 *
 * @param tokenProvider Abstraction supplying the currently persisted session token.
 */
class AuthInterceptor @Inject constructor(
    private val tokenProvider: TokenProvider
) : Interceptor {

    /**
     * Intercepts the outgoing OkHttp request chain, inspecting [TokenProvider] for a valid session token.
     *
     * If a token is present, appends the `Authorization: Bearer <token>` header to the request.
     * If no token is available, proceeds with the unmodified request.
     *
     * @param chain The active OkHttp execution chain.
     * @return The response resulting from proceeding along the chain.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        val token  = tokenProvider.getToken()

        if (token != null) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }
}