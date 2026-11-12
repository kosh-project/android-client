package project.kosh.core.network

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import project.kosh.core.crypto.AesGcmEnvelope
import java.io.ByteArrayInputStream

class StreamingEncryptedRequestBodyTest {

    @Test
    fun contentLength_returnsExactPayloadSizePlusEnvelopeOverhead() {
        val dek = ByteArray(32) { 1 }
        val plaintextSize = 1024L * 1024L
        val body = StreamingEncryptedRequestBody(
            plaintextSize = plaintextSize,
            dek = dek,
            inputStreamProvider = { ByteArrayInputStream(ByteArray(0)) }
        )

        assertEquals(plaintextSize + 28L, body.contentLength())
        assertEquals("application/octet-stream", body.contentType()?.toString())
    }

    @Test
    fun writeTo_streamsEncryptedBytesDecryptableByStandardDecrypt() {
        val dek = ByteArray(32) { (it * 3).toByte() }
        val testData = ByteArray(128 * 1024) { (it % 255).toByte() }

        val body = StreamingEncryptedRequestBody(
            plaintextSize = testData.size.toLong(),
            dek = dek,
            inputStreamProvider = { ByteArrayInputStream(testData) }
        )

        val buffer = Buffer()
        body.writeTo(buffer)

        val writtenBytes = buffer.readByteArray()
        assertEquals((testData.size + 28).toLong(), writtenBytes.size.toLong())

        val decrypted = AesGcmEnvelope.decrypt(writtenBytes, dek)
        assertArrayEquals(testData, decrypted)
    }

    @Test
    fun progressRequestBody_tracksStreamingProgressGranularly() {
        val dek = ByteArray(32) { 4 }
        val testData = ByteArray(256 * 1024) { (it % 240).toByte() }

        val streamingBody = StreamingEncryptedRequestBody(
            plaintextSize = testData.size.toLong(),
            dek = dek,
            inputStreamProvider = { ByteArrayInputStream(testData) }
        )

        val progressReports = mutableListOf<Long>()
        val progressBody = ProgressRequestBody(streamingBody) { sent, total ->
            progressReports.add(sent)
            assertEquals((testData.size + 28).toLong(), total)
        }

        val buffer = Buffer()
        progressBody.writeTo(buffer)

        assert(progressReports.size >= 2)
        assertEquals((testData.size + 28).toLong(), progressReports.last())
    }

    @Test
    fun progressRequestBody_delegatesIsOneShotAndIsDuplex() {
        val dek = ByteArray(32) { 5 }
        val streamingBody = StreamingEncryptedRequestBody(
            plaintextSize = 100L,
            dek = dek,
            inputStreamProvider = { ByteArrayInputStream(ByteArray(100)) }
        )
        val progressBody = ProgressRequestBody(streamingBody) { _, _ -> }
        assertEquals(true, streamingBody.isOneShot())
        assertEquals(true, progressBody.isOneShot())
        assertEquals(false, progressBody.isDuplex())
    }
}
