package project.kosh.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import project.kosh.core.crypto.Bip39Sas
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Result representing the outcome of a TOFU certificate probe to a Kosh server node.
 *
 * @property host Server host name or IP address probed.
 * @property port Server TCP port probed.
 * @property isTls Whether the probe was conducted over TLS (HTTPS) or unencrypted HTTP.
 * @property fingerprintBytes 32-byte SHA-256 fingerprint of the presented leaf X.509 certificate.
 * @property fingerprintHex 64-character lowercase hex encoding of the certificate fingerprint.
 * @property sasWords 12 BIP-39 mnemonic verification words derived from the first 16 bytes of the fingerprint.
 * @property subject Subject Distinguished Name (DN) from the certificate, or empty if plaintext.
 * @property issuer Issuer Distinguished Name (DN) from the certificate, or empty if plaintext.
 */
data class CertificateProbeResult(
    val host: String,
    val port: Int,
    val isTls: Boolean,
    val fingerprintBytes: ByteArray,
    val fingerprintHex: String,
    val sasWords: List<String>,
    val subject: String,
    val issuer: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CertificateProbeResult

        if (host != other.host) return false
        if (port != other.port) return false
        if (isTls != other.isTls) return false
        if (!fingerprintBytes.contentEquals(other.fingerprintBytes)) return false
        if (fingerprintHex != other.fingerprintHex) return false
        if (sasWords != other.sasWords) return false
        if (subject != other.subject) return false
        if (issuer != other.issuer) return false

        return true
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + isTls.hashCode()
        result = 31 * result + fingerprintBytes.contentHashCode()
        result = 31 * result + fingerprintHex.hashCode()
        result = 31 * result + sasWords.hashCode()
        result = 31 * result + subject.hashCode()
        result = 31 * result + issuer.hashCode()
        return result
    }
}

/**
 * Low-level network service executing Trust-On-First-Use (TOFU) certificate inspection.
 *
 * Directs an unpinned handshake to the server's `/health` endpoint using a capturing
 * [X509TrustManager] to intercept the peer's leaf certificate before formal pinning occurs.
 * Computes the 32-byte SHA-256 digest and derives 12 BIP-39 SAS words for out-of-band verification.
 */
@Singleton
class CertificateProber @Inject constructor() {

    /**
     * Probes the server at the given endpoint and extracts certificate details.
     *
     * @param host Target host name or IPv4/IPv6 address.
     * @param port Target TCP port (1..65535).
     * @param useTls Whether to probe via HTTPS or fallback HTTP.
     * @return [Result] enclosing [CertificateProbeResult] on success, or an exception on failure.
     */
    suspend fun probeServer(host: String, port: Int, useTls: Boolean): Result<CertificateProbeResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanHost = host.trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .trimEnd('/')

                if (cleanHost.isBlank() || port !in 1..65535) {
                    throw IllegalArgumentException("Invalid server host or port: $host:$port")
                }

                if (!useTls) {
                    val client = OkHttpClient.Builder()
                        .dns(KoshDns)
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder()
                        .url("http://$cleanHost:$port/health")
                        .get()
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("Server returned HTTP ${response.code} on /health")
                        }
                    }

                    return@runCatching CertificateProbeResult(
                        host = cleanHost,
                        port = port,
                        isTls = false,
                        fingerprintBytes = ByteArray(0),
                        fingerprintHex = "",
                        sasWords = emptyList(),
                        subject = "Plaintext HTTP",
                        issuer = "None"
                    )
                }

                var capturedLeafCert: X509Certificate? = null

                val capturingTrustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        capturedLeafCert = chain?.firstOrNull()
                    }
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }

                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<TrustManager>(capturingTrustManager), SecureRandom())
                }

                val probeClient = OkHttpClient.Builder()
                    .dns(KoshDns)
                    .sslSocketFactory(sslContext.socketFactory, capturingTrustManager)
                    .hostnameVerifier { _, _ -> true }
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("https://$cleanHost:$port/health")
                    .get()
                    .build()

                probeClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Server returned HTTP ${response.code} on /health")
                    }
                }

                val cert = capturedLeafCert
                    ?: throw IllegalStateException("TLS handshake completed but no certificate was captured")

                val derBytes = cert.encoded
                val md = MessageDigest.getInstance("SHA-256")
                val fingerprintBytes = md.digest(derBytes)
                val fingerprintHex = ServerConfigurationStore.bytesToHex(fingerprintBytes)
                val sasWords = Bip39Sas.deriveWords(fingerprintBytes)

                CertificateProbeResult(
                    host = cleanHost,
                    port = port,
                    isTls = true,
                    fingerprintBytes = fingerprintBytes,
                    fingerprintHex = fingerprintHex,
                    sasWords = sasWords,
                    subject = cert.subjectDN?.name.orEmpty(),
                    issuer = cert.issuerDN?.name.orEmpty()
                )
            }
        }

    /**
     * Automatically probes the server endpoint by first attempting a TLS connection,
     * and seamlessly falling back to unencrypted HTTP if TLS is not active.
     * Useful for local development and ADB reverse debugging where server mode may vary.
     *
     * @param host Target host name or IPv4 address (e.g. 127.0.0.1, 10.0.2.2).
     * @param port Target TCP port.
     * @return [Result] enclosing [CertificateProbeResult] on success, or an exception on failure.
     */
    suspend fun autoProbe(host: String, port: Int): Result<CertificateProbeResult> =
        withContext(Dispatchers.IO) {
            val tlsResult = probeServer(host, port, useTls = true)
            if (tlsResult.isSuccess) {
                return@withContext tlsResult
            }
            val httpResult = probeServer(host, port, useTls = false)
            if (httpResult.isSuccess) {
                return@withContext httpResult
            }
            tlsResult
        }
}
