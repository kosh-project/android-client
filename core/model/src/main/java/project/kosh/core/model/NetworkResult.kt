package project.kosh.core.model

import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>

    sealed interface Failure : NetworkResult<Nothing> {
        val userMessage: String
        val cause: Throwable?
    }

    sealed interface Connectivity : Failure {
        data class NetworkUnavailable(
            override val userMessage: String = "No network connection. Please check your Wi-Fi",
            override val cause: Throwable? = null
        ) : Connectivity

        data class HostUnreachable(
            val host: String,
            override val userMessage: String = "Cannot resolve server address ($host). Is mDNS/Avahi running?",
            override val cause: Throwable? = null
        ) : Connectivity

        data class ConnectionRefused(
            val port: Int = 6969,
            override val userMessage: String = "Connection refused on port $port. Is Kosh server running",
            override val cause: Throwable? = null
        ) : Connectivity

        data class ConnectionTimeout(
            override val userMessage: String = "Timed out connecting to server. Please check the server IP.",
            override val cause: Throwable? = null
        ) : Connectivity

    }

    sealed interface Socket : Failure {
        data class ReadTimeout(
            override val userMessage: String = "Server took too long to respond (read timeout).",
            override val cause: Throwable? = null
        ) : Socket

        data class WriteTimeout(
            override val userMessage: String = "Upload stalled and timed out during transfer.",
            override val cause: Throwable? = null,
        ) : Socket

        data class ConnectionReset(
            override val userMessage: String = "Connection was reset by the server mid-transfer.",
            override val cause: Throwable? = null
        ) : Socket
    }

    sealed interface Security : Failure {
        data class TlsFailed(
            val reason: String,
            override val userMessage: String = "TLS Certificate verification failed: $reason",
            override val cause: Throwable? = null
        ) : Security
    }

    sealed interface Protocol : Failure {
        data class Serialization(
            val details: String,
            override val userMessage: String = "Failed to parse server response",
            override val cause: Throwable? = null
        ) : Protocol
    }

    sealed interface Http : Failure {
        val code: Int
        val serverMessage: String

        data class Unauthorized(
            override val serverMessage: String,
            val isChallengeExpired: Boolean = false
        ) : Http {
            override val code: Int
                get() = 401
            override val userMessage: String
                get() = if (isChallengeExpired) {
                    "Authentication challenge expired. Please retry."
                } else {
                    "Invalid credentials or session expired."
                }
            override val cause: Throwable?
                get() = null
        }

        data class Conflict(
            override val serverMessage: String,
        ) : Http {
            override val cause: Throwable?
                get() = null
            override val code: Int
                get() = 409
            override val userMessage: String
                get() = "User identity is already registered."
        }

        data class NotFound(
            val resource: String = "",
            override val serverMessage: String
        ) : Http {
            override val code: Int
                get() = 404
            override val cause: Throwable?
                get() = null
            override val userMessage: String
                get() = "Requested asset was not found on server."
        }

        data class PayloadTooLarge(
            override val serverMessage: String = "Payload exceeds maximum server limit (10GB)"
        ) : Http {
            override val cause: Throwable?
                get() = null
            override val code: Int
                get() = 413
            override val userMessage: String
                get() = "File is too large for server vault"
        }

        data class RateLimited(
            val retryAfterSeconds: Int? = null,
            override val serverMessage: String = "Rate limit exceeded"
        ) : Http {
            override val cause: Throwable?
                get() = null
            override val code: Int
                get() = 429
            override val userMessage: String
                get() = "Too many reqeusts. Slow down bud"
        }

        data class ClientError(
            override val code: Int,
            override val serverMessage: String,
        ) : Http {
            override val cause: Throwable?
                get() = null
            override val userMessage: String
                get() = "Request error ($code): $serverMessage"
        }

        data class ServerError(
            override val code: Int = 500,
            override val serverMessage: String,
        ) : Http {
            override val cause: Throwable?
                get() = null
            override val userMessage: String
                get() = "Server internal error ($code): $serverMessage"
        }


    }

    data class Unknown(
        override val userMessage: String,
        override val cause: Throwable? = null
    ) : Failure

    companion object {
        fun fromThrowable(t: Throwable): Failure {
            val msg = t.message?.lowercase() ?: ""
            return when (t) {
                is UnknownHostException -> Connectivity.HostUnreachable(
                    t.message ?: "unknown host", cause = t
                )

                is NoRouteToHostException -> Connectivity.NetworkUnavailable(
                    "No route to host",
                    t
                )

                is ConnectException -> {
                    if (msg.contains("refused")) Connectivity.ConnectionRefused(cause = t)
                    else Connectivity.NetworkUnavailable("Network Unreachable", t)
                }

                is SocketTimeoutException -> {
                    when {
                        msg.contains("connect") -> Connectivity.ConnectionTimeout(cause = t)
                        msg.contains("write") -> Socket.WriteTimeout(cause = t)
                        else -> Socket.ReadTimeout(cause = t)
                    }
                }

                is SSLException -> Security.TlsFailed(
                    t.localizedMessage ?: "SSL Error",
                    cause = t
                )

                is SocketException -> {
                    if (msg.contains("reset") || msg.contains("broken pipe")) {
                        Socket.ConnectionReset(
                            cause = t
                        )
                    } else Connectivity.NetworkUnavailable(
                        t.localizedMessage ?: "Socket Error",
                        t
                    )
                }

                is SerializationException -> Protocol.Serialization(
                    t.localizedMessage ?: "JSON Error", cause = t
                )

                else -> Unknown(t.localizedMessage ?: "Unknown failure", t)
            }
        }

        fun fromHttp(code: Int, body: String): Http {
            val trimmedBody = body.trim()
            return when (code) {
                401 -> Http.Unauthorized(
                    serverMessage = trimmedBody,
                    isChallengeExpired = trimmedBody.contains("expired", ignoreCase = true)
                )

                404 -> Http.NotFound(serverMessage = trimmedBody)
                409 -> Http.Conflict(serverMessage = trimmedBody)
                413 -> Http.PayloadTooLarge(serverMessage = trimmedBody)
                429 -> Http.RateLimited(serverMessage = trimmedBody)
                in 400..499 -> Http.ClientError(code, trimmedBody)
                else -> Http.ServerError(code, trimmedBody)
            }
        }
    }
}