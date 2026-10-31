package project.kosh.core.network

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

class KoshPinnedTrustManagerTest {

    private fun createMockCertificate(derBytes: ByteArray): X509Certificate {
        val cert = mockk<X509Certificate>()
        every { cert.encoded } returns derBytes
        return cert
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    @Test
    fun `checkServerTrusted succeeds when certificate SHA-256 matches pinned fingerprint`() {
        val certDer = "synthetic DER certificate content".toByteArray(Charsets.UTF_8)
        val expectedFingerprint = sha256(certDer)

        val trustManager = KoshPinnedTrustManager(expectedFingerprint)
        val mockCert = createMockCertificate(certDer)

        // Should not throw
        trustManager.checkServerTrusted(arrayOf(mockCert), "ECDHE_RSA")
    }

    @Test
    fun `checkServerTrusted throws CertificateException when fingerprint does not match`() {
        val certDer = "genuine certificate bytes".toByteArray(Charsets.UTF_8)
        val attackerCertDer = "attacker MitM certificate bytes".toByteArray(Charsets.UTF_8)

        val expectedFingerprint = sha256(certDer)
        val trustManager = KoshPinnedTrustManager(expectedFingerprint)
        val attackerCert = createMockCertificate(attackerCertDer)

        val exception = assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(attackerCert), "ECDHE_RSA")
        }

        assertTrue(exception.message!!.contains("Certificate pinning failed! Potential MitM attack"))
    }

    @Test
    fun `checkServerTrusted throws CertificateException when chain is empty or null`() {
        val fingerprint = ByteArray(32) { 0x01 }
        val trustManager = KoshPinnedTrustManager(fingerprint)

        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(null, "RSA")
        }

        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(emptyArray(), "RSA")
        }
    }

    @Test
    fun `checkServerTrusted throws CertificateException when no fingerprint is pinned`() {
        val trustManager = KoshPinnedTrustManager { null }
        val mockCert = createMockCertificate("any cert".toByteArray())

        val exception = assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(mockCert), "RSA")
        }
        assertTrue(exception.message!!.contains("No certificate fingerprint pinned"))
    }

    @Test
    fun `checkServerTrusted throws CertificateException when pinned fingerprint length is not 32 bytes`() {
        val invalidFingerprint = ByteArray(16) { 0x01 }
        val trustManager = KoshPinnedTrustManager(invalidFingerprint)
        val mockCert = createMockCertificate("any cert".toByteArray())

        val exception = assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(mockCert), "RSA")
        }
        assertTrue(exception.message!!.contains("Invalid pinned fingerprint length"))
    }

    @Test
    fun `trust manager dynamically checks updated fingerprint from provider`() {
        var currentFingerprint: ByteArray? = null
        val trustManager = KoshPinnedTrustManager { currentFingerprint }

        val certDer = "server certificate DER".toByteArray()
        val certFingerprint = sha256(certDer)
        val mockCert = createMockCertificate(certDer)

        // Fails when unpinned
        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(mockCert), "RSA")
        }

        // Succeeds after pairing updates fingerprint
        currentFingerprint = certFingerprint
        trustManager.checkServerTrusted(arrayOf(mockCert), "RSA")
    }

    @Test
    fun `checkClientTrusted and getAcceptedIssuers adhere to contract`() {
        val trustManager = KoshPinnedTrustManager(ByteArray(32))

        // checkClientTrusted does not throw
        trustManager.checkClientTrusted(null, "RSA")
        trustManager.checkClientTrusted(emptyArray(), "RSA")

        // getAcceptedIssuers returns empty array
        assertArrayEquals(emptyArray<X509Certificate>(), trustManager.acceptedIssuers)
    }
}
