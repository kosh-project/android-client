package project.kosh.core.network

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Result representing the outcome of a server health probe and latency ping measurement.
 */
sealed interface PingResult {
    /**
     * The server responded successfully to GET /health.
     *
     * @param latencyMs Measured round-trip network and TLS handshake latency in milliseconds.
     * @param statusCode HTTP response status code (typically 200).
     * @param isTls Whether the probe was executed over TLS (HTTPS).
     */
    data class Success(
        val latencyMs: Long,
        val statusCode: Int,
        val isTls: Boolean
    ) : PingResult

    /**
     * The connection or probe failed.
     *
     * @param error Diagnostic reason for failure.
     * @param isTls Whether the probe was attempted over TLS (HTTPS).
     */
    data class Failure(
        val error: String,
        val isTls: Boolean
    ) : PingResult
}

/**
 * Low-level network service measuring round-trip latency and connectivity to Kosh storage nodes.
 *
 * Directs an unpinned HTTP/HTTPS GET request to `/health`. Uses a dedicated short-lived
 * permissive trust manager during probe testing so that self-signed local LAN servers can be
 * pinged and validated before formal certificate pinning occurs.
 *
 * Employs [SystemClock.elapsedRealtime] for monotonic time delta calculation immune to wall-clock shifts.
 */
@Singleton
class ServerHealthPinger @Inject constructor() {

    private val permissiveTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslSocketFactory by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(permissiveTrustManager), SecureRandom())
        sslContext.socketFactory
    }

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .dns(KoshDns)
            .sslSocketFactory(sslSocketFactory, permissiveTrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Probes the target server's `/health` endpoint and measures round-trip latency.
     *
     * @param host Target host name or IPv4/IPv6 address.
     * @param port Target TCP port.
     * @param useTls Whether to negotiate TLS (HTTPS) or use plaintext HTTP.
     * @return [PingResult.Success] with latency in milliseconds, or [PingResult.Failure].
     */
    suspend fun ping(host: String, port: Int, useTls: Boolean): PingResult =
        withContext(Dispatchers.IO) {
            val cleanHost = host.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')

            if (cleanHost.isBlank() || port !in 1..65535) {
                return@withContext PingResult.Failure("Invalid host or port", useTls)
            }

            val scheme = if (useTls) "https" else "http"
            val url = "$scheme://$cleanHost:$port/health"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val startTime = SystemClock.elapsedRealtime()
            try {
                probeClient.newCall(request).execute().use { response ->
                    val latencyMs = SystemClock.elapsedRealtime() - startTime
                    if (response.isSuccessful) {
                        PingResult.Success(
                            latencyMs = latencyMs,
                            statusCode = response.code,
                            isTls = useTls
                        )
                    } else {
                        PingResult.Failure(
                            error = "HTTP ${response.code} ${response.message}",
                            isTls = useTls
                        )
                    }
                }
            } catch (e: Exception) {
                val errorMsg = when (e) {
                    is java.net.ConnectException -> "Connection refused"
                    is java.net.SocketTimeoutException -> "Timeout (3s)"
                    is java.net.UnknownHostException -> "Host unresolvable"
                    is javax.net.ssl.SSLException -> "TLS error: ${e.message ?: e.javaClass.simpleName}"
                    else -> e.message ?: e.javaClass.simpleName
                }
                PingResult.Failure(error = errorMsg, isTls = useTls)
            }
        }
}
