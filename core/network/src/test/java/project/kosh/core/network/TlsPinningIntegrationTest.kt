package project.kosh.core.network

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException

/**
 * End-to-end integration test executing real TLS 1.3/1.2 socket handshakes against an active local
 * HTTPS server configured with a self-signed X.509 certificate.
 *
 * Verifies that [KoshPinnedTrustManager] and [PinnedHttpClientFactory]:
 * 1. Successfully negotiate the TLS handshake when the peer leaf certificate SHA-256 matches the pinned fingerprint.
 * 2. Terminate the TLS handshake with [SSLHandshakeException] when an attacker or incorrect fingerprint is configured.
 * 3. Terminate the TLS handshake when no certificate fingerprint is pinned.
 * 4. Satisfy hostname verification for RFC 1918 loopback IPs (`127.0.0.1`) under self-signed TLS.
 */
class TlsPinningIntegrationTest {

    companion object {
        private lateinit var httpsServer: HttpsServer
        private lateinit var correctFingerprint: ByteArray
        private var serverPort: Int = 0

        @BeforeClass
        @JvmStatic
        fun startHttpsServer() {
            val keyStoreStream = TlsPinningIntegrationTest::class.java.classLoader
                ?.getResourceAsStream("test_keystore.p12")
                ?: throw IllegalStateException("Resource test_keystore.p12 not found")

            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(keyStoreStream, "password".toCharArray())

            val leafCert = keyStore.getCertificate("test") as X509Certificate
            correctFingerprint = MessageDigest.getInstance("SHA-256").digest(leafCert.encoded)

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, "password".toCharArray())

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, null)

            httpsServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            httpsServer.httpsConfigurator = object : HttpsConfigurator(sslContext) {
                override fun configure(params: HttpsParameters) {
                    params.setSSLParameters(sslContext.defaultSSLParameters)
                }
            }

            httpsServer.createContext("/health") { exchange ->
                val response = "{\"health\":\"ok\"}".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }

            httpsServer.start()
            serverPort = httpsServer.address.port
        }

        @AfterClass
        @JvmStatic
        fun stopHttpsServer() {
            if (::httpsServer.isInitialized) {
                httpsServer.stop(0)
            }
        }
    }

    @Test
    fun `real TLS handshake succeeds and receives HTTP 200 when pinned fingerprint matches`() {
        val client = PinnedHttpClientFactory.create(correctFingerprint)

        val request = Request.Builder()
            .url("https://127.0.0.1:$serverPort/health")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
            assertEquals(200, response.code)
            assertEquals("{\"health\":\"ok\"}", response.body.string())
        }
    }

    @Test
    fun `real TLS handshake fails with SSLHandshakeException when pinned fingerprint does not match`() {
        val attackerFingerprint = ByteArray(32) { 0xee.toByte() }
        val client = PinnedHttpClientFactory.create(attackerFingerprint)

        val request = Request.Builder()
            .url("https://127.0.0.1:$serverPort/health")
            .get()
            .build()

        val exception = assertThrows(SSLHandshakeException::class.java) {
            client.newCall(request).execute().use { response ->
                response.body.string()
            }
        }
        assertTrue(exception.cause?.message?.contains("Certificate pinning failed") == true ||
                exception.message?.contains("Certificate pinning failed") == true)
    }

    @Test
    fun `real TLS handshake fails when no fingerprint is pinned`() {
        val trustManager = KoshPinnedTrustManager { null }
        val (socketFactory, tm) = PinnedHttpClientFactory.createSslSocketFactory(trustManager)
        val client = OkHttpClient.Builder()
            .sslSocketFactory(socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()

        val request = Request.Builder()
            .url("https://127.0.0.1:$serverPort/health")
            .get()
            .build()

        val exception = assertThrows(SSLHandshakeException::class.java) {
            client.newCall(request).execute().use { response ->
                response.body.string()
            }
        }
        assertTrue(exception.cause?.message?.contains("No certificate fingerprint pinned") == true ||
                exception.message?.contains("No certificate fingerprint pinned") == true)
    }
}
