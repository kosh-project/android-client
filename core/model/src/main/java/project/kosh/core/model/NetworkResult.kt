package project.kosh.core.model

import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A sealed algebraic data type (ADT) representing the outcome of network operations.
 *
 * This taxonomy bridges the gap between raw Android OS/kernel-level network exceptions,
 * HTTP transport semantics, and high-level user interface states.
 *
 * Every network call returns either:
 * - [Success] containing the parsed data payload of type [T].
 * - [Failure] containing a user-friendly [Failure.userMessage] suitable for display on UI screens,
 *   as well as an optional [Failure.cause] preserving the root exception for logging and debugging.
 *
 * @param T The type of data returned upon success.
 */
sealed interface NetworkResult<out T> {

    /**
     * Represents a successful network operation.
     *
     * @property data The response payload returned by the server.
     */
    data class Success<T>(val data: T) : NetworkResult<T>

    /**
     * Root sealed interface for all failure scenarios in the network pipeline.
     */
    sealed interface Failure : NetworkResult<Nothing> {
        /**
         * A human-readable, user-friendly message suitable for rendering in the UI.
         */
        val userMessage: String

        /**
         * The underlying exception or throwable, if available, for diagnostic logging.
         */
        val cause: Throwable?
    }

    /**
     * Subtree representing network reachability, routing, and connection establishment failures.
     */
    sealed interface Connectivity : Failure {

        /**
         * Indicates that the device has no active network interface or local routing capability
         * (for example, Wi-Fi or cellular data is disconnected).
         *
         * @property userMessage User-facing guidance to verify local network connectivity.
         * @property cause The original network throwable.
         */
        data class NetworkUnavailable(
            override val userMessage: String = "No network connection. Please check your Wi-Fi",
            override val cause: Throwable? = null
        ) : Connectivity

        /**
         * Indicates that DNS or local mDNS/Avahi resolution failed for the specified host.
         *
         * @property host The unresolvable hostname or IP string.
         * @property userMessage Diagnostic prompt checking mDNS/Avahi status.
         * @property cause The underlying [UnknownHostException].
         */
        data class HostUnreachable(
            val host: String,
            override val userMessage: String = "Cannot resolve server address ($host). Is mDNS/Avahi running?",
            override val cause: Throwable? = null
        ) : Connectivity

        /**
         * Indicates that a TCP RST was received from the target host on the specified port,
         * meaning no process is currently listening on that port.
         *
         * @property port The destination TCP port (defaults to Kosh default 6969).
         * @property userMessage Prompt verifying that the Kosh backend daemon is running.
         * @property cause The underlying [ConnectException].
         */
        data class ConnectionRefused(
            val port: Int = 6969,
            override val userMessage: String = "Connection refused on port $port. Is Kosh server running",
            override val cause: Throwable? = null
        ) : Connectivity

        /**
         * Indicates that the initial TCP three-way handshake timed out before a socket could be established.
         *
         * @property userMessage Prompt to verify server IP configuration.
         * @property cause The underlying [SocketTimeoutException].
         */
        data class ConnectionTimeout(
            override val userMessage: String = "Timed out connecting to server. Please check the server IP.",
            override val cause: Throwable? = null
        ) : Connectivity
    }

    /**
     * Subtree representing socket I/O timeouts and interruptions on an active TCP connection.
     */
    sealed interface Socket : Failure {

        /**
         * Indicates that the server accepted the TCP connection but failed to transmit response
         * bytes within the configured read timeout window.
         *
         * @property userMessage User-facing read timeout notification.
         * @property cause The underlying [SocketTimeoutException].
         */
        data class ReadTimeout(
            override val userMessage: String = "Server took too long to respond (read timeout).",
            override val cause: Throwable? = null
        ) : Socket

        /**
         * Indicates that an upload stalled while streaming bytes to the server socket
         * and exceeded the configured write timeout.
         *
         * @property userMessage User-facing write timeout notification.
         * @property cause The underlying [SocketTimeoutException].
         */
        data class WriteTimeout(
            override val userMessage: String = "Upload stalled and timed out during transfer.",
            override val cause: Throwable? = null,
        ) : Socket

        /**
         * Indicates that the TCP connection was abruptly terminated or reset mid-transfer
         * (for example, via an `ECONNRESET` or broken pipe).
         *
         * @property userMessage User-facing connection reset notification.
         * @property cause The underlying [SocketException].
         */
        data class ConnectionReset(
            override val userMessage: String = "Connection was reset by the server mid-transfer.",
            override val cause: Throwable? = null
        ) : Socket
    }

    /**
     * Subtree representing cryptographic transport and TLS handshake failures.
     */
    sealed interface Security : Failure {

        /**
         * Indicates that TLS certificate verification, trust manager validation,
         * or certificate pinning checks failed.
         *
         * @property reason A description of the cryptographic verification failure.
         * @property userMessage User-facing warning regarding TLS validation failure.
         * @property cause The underlying [SSLException].
         */
        data class TlsFailed(
            val reason: String,
            override val userMessage: String = "TLS Certificate verification failed: $reason",
            override val cause: Throwable? = null
        ) : Security
    }

    /**
     * Subtree representing application-layer protocol and framing failures.
     */
    sealed interface Protocol : Failure {

        /**
         * Indicates that the response body could not be parsed into the expected data model,
         * signaling a wire schema mismatch or malformed JSON payload.
         *
         * @property details Diagnostic details regarding the parsing failure.
         * @property userMessage User-facing message indicating response parse failure.
         * @property cause The underlying [SerializationException].
         */
        data class Serialization(
            val details: String,
            override val userMessage: String = "Failed to parse server response",
            override val cause: Throwable? = null
        ) : Protocol
    }

    /**
     * Subtree representing HTTP status code responses in the 4xx and 5xx ranges.
     */
    sealed interface Http : Failure {
        /**
         * The HTTP response status code.
         */
        val code: Int

        /**
         * The raw response body string returned by the server.
         */
        val serverMessage: String

        /**
         * HTTP 401 Unauthorized.
         *
         * Signals that the request lacked valid authentication credentials or that
         * a Proof-of-Work challenge expired prior to submission.
         *
         * @property serverMessage The raw message string returned by the server.
         * @property isChallengeExpired `true` if the failure was caused by the 15-second
         *   Hashcash challenge TTL expiring; `false` if caused by invalid credentials or a revoked token.
         */
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

        /**
         * HTTP 409 Conflict.
         *
         * Typically returned during registration when the requested identity hash
         * already exists in the backend user store.
         *
         * @property serverMessage The raw message string returned by the server.
         */
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

        /**
         * HTTP 404 Not Found.
         *
         * Returned when the requested endpoint or Content-Addressable Storage (CAS)
         * content hash does not exist on the server.
         *
         * @property resource The resource path or content hash that was not found.
         * @property serverMessage The raw message string returned by the server.
         */
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

        /**
         * HTTP 413 Payload Too Large.
         *
         * Returned when an uploaded asset exceeds the server host's configured ceiling (10 GB).
         *
         * @property serverMessage The raw message string returned by the server.
         */
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

        /**
         * HTTP 429 Too Many Requests.
         *
         * Returned when an ingress rate governor triggers (global IP governor, auth IP governor,
         * or device token governor).
         *
         * @property retryAfterSeconds Recommended cool-down period in seconds, if specified by the server.
         * @property serverMessage The raw message string returned by the server.
         */
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

        /**
         * Generic HTTP 4xx Client Error for client-side errors not matched by specialized classes.
         *
         * @property code The 4xx HTTP status code.
         * @property serverMessage The raw message string returned by the server.
         */
        data class ClientError(
            override val code: Int,
            override val serverMessage: String,
        ) : Http {
            override val cause: Throwable?
                get() = null
            override val userMessage: String
                get() = "Request error ($code): $serverMessage"
        }

        /**
         * HTTP 5xx Server Error indicating an internal error on the backend host.
         *
         * @property code The 5xx HTTP status code (defaults to 500).
         * @property serverMessage The raw message string returned by the server.
         */
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

    /**
     * Fallback failure representation for unanticipated exceptions and errors.
     *
     * @property userMessage A description of the unknown failure.
     * @property cause The originating throwable, if available.
     */
    data class Unknown(
        override val userMessage: String,
        override val cause: Throwable? = null
    ) : Failure

    companion object {
        /**
         * Translates an arbitrary [Throwable] into a typed [Failure] hierarchy instance.
         *
         * Inspects low-level Java/Android network exception types and messages to classify
         * connectivity, socket, security, and serialization errors into actionable models.
         *
         * @param t The caught exception or error.
         * @return The classified [Failure] instance.
         */
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

        /**
         * Translates an HTTP status code and response body string into a typed [Http] failure instance.
         *
         * Extracts specialized contextual information, such as inspecting 401 response bodies
         * to identify expired Proof-of-Work challenges.
         *
         * @param code The HTTP status code (e.g. 401, 404, 409, 413, 429, 500).
         * @param body The raw text body returned by the server.
         * @return The classified [Http] failure model.
         */
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