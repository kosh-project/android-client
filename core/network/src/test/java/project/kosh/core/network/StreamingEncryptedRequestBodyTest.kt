package project.kosh.core.network

import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import project.kosh.core.crypto.AesGcmEnvelope
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    fun writeTo_emptyStream_producesValid28ByteEnvelope() {
        val dek = ByteArray(32) { 9 }
        val body = StreamingEncryptedRequestBody(
            plaintextSize = 0L,
            dek = dek,
            inputStreamProvider = { ByteArrayInputStream(ByteArray(0)) }
        )

        val buffer = Buffer()
        body.writeTo(buffer)

        val writtenBytes = buffer.readByteArray()
        assertEquals(28L, writtenBytes.size.toLong())

        val decrypted = AesGcmEnvelope.decrypt(writtenBytes, dek)
        assertEquals(0, decrypted.size)
    }

    @Test
    fun writeTo_boundedChannelBackpressure_pausesAndSuccessfullyStreamsAllChunks() {
        val dek = ByteArray(32) { (it + 5).toByte() }
        // 32 chunks of 1 KiB with queue capacity of only 2 chunks
        val testData = ByteArray(32 * 1024) { (it % 127).toByte() }

        val body = StreamingEncryptedRequestBody(
            plaintextSize = testData.size.toLong(),
            dek = dek,
            chunkSize = 1024,
            queueCapacity = 2,
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
    fun writeTo_producerError_propagatesIOExceptionToConsumer() {
        val dek = ByteArray(32) { 2 }
        val failingStream = object : InputStream() {
            private var bytesRead = 0
            override fun read(): Int {
                if (++bytesRead > 100) throw IOException("Simulated disk read error")
                return 42
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesRead > 100) throw IOException("Simulated disk read error")
                val toRead = minOf(len, 100)
                for (i in 0 until toRead) {
                    b[off + i] = 42
                }
                bytesRead += toRead
                return toRead
            }
        }

        val body = StreamingEncryptedRequestBody(
            plaintextSize = 1000L,
            dek = dek,
            chunkSize = 50,
            queueCapacity = 2,
            inputStreamProvider = { failingStream }
        )

        val buffer = Buffer()
        assertThrows(IOException::class.java) {
            body.writeTo(buffer)
        }
    }

    @Test
    fun writeTo_consumerAbort_interruptsProducerAndClosesStream() {
        val dek = ByteArray(32) { 3 }
        val streamClosed = AtomicBoolean(false)
        val testData = ByteArray(100 * 1024) { 1 }

        val trackedStream = object : ByteArrayInputStream(testData) {
            override fun close() {
                super.close()
                streamClosed.set(true)
            }
        }

        val body = StreamingEncryptedRequestBody(
            plaintextSize = testData.size.toLong(),
            dek = dek,
            chunkSize = 1024,
            queueCapacity = 2,
            inputStreamProvider = { trackedStream }
        )

        // Custom sink that aborts after first chunk write
        val rawBuffer = Buffer()
        val writeCount = AtomicInteger(0)
        val abortingSink = object : ForwardingSink(rawBuffer) {
            override fun write(source: Buffer, byteCount: Long) {
                if (writeCount.incrementAndGet() > 2) {
                    throw IOException("Simulated network connection reset")
                }
                super.write(source, byteCount)
            }
        }.buffer()

        assertThrows(IOException::class.java) {
            body.writeTo(abortingSink)
        }

        // Verify stream was closed promptly on failure
        assertTrue("Input stream should be closed when upload aborts", streamClosed.get())
    }

    @Test
    fun writeTo_plaintextSizeMismatch_throwsIOException() {
        val dek = ByteArray(32) { 7 }
        val testData = ByteArray(500) { 1 }

        val body = StreamingEncryptedRequestBody(
            plaintextSize = 1000L, // declares 1000 bytes but stream only provides 500
            dek = dek,
            chunkSize = 100,
            inputStreamProvider = { ByteArrayInputStream(testData) }
        )

        val buffer = Buffer()
        val ex = assertThrows(IOException::class.java) {
            body.writeTo(buffer)
        }
        assertTrue(ex.message?.contains("size mismatch") == true || ex.cause?.message?.contains("size mismatch") == true)
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

        assertTrue(progressReports.size >= 2)
        assertEquals((testData.size + 28).toLong(), progressReports.last())
    }

    @Test
    fun progressRequestBody_flushesEveryChunkDirectly() {
        val dek = ByteArray(32) { 8 }
        val testData = ByteArray(64 * 1024) { (it % 100).toByte() }

        val streamingBody = StreamingEncryptedRequestBody(
            plaintextSize = testData.size.toLong(),
            dek = dek,
            chunkSize = 16 * 1024, // 4 chunks
            queueCapacity = 2,
            inputStreamProvider = { ByteArrayInputStream(testData) }
        )

        val flushCount = AtomicInteger(0)
        val rawBuffer = Buffer()
        val trackingSink = object : ForwardingSink(rawBuffer) {
            override fun flush() {
                flushCount.incrementAndGet()
                super.flush()
            }
        }.buffer()

        val progressBody = ProgressRequestBody(streamingBody) { _, _ -> }
        progressBody.writeTo(trackingSink)

        // Must flush at least IV chunk + 4 data chunks + final chunk + completion flush
        assertTrue("Expected multiple flushes during streaming upload, got ${flushCount.get()}", flushCount.get() >= 5)
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

    @Test
    fun streamingEncryptedRequestBody_reportsProgressDirectlyWithoutWrapper() {
        val dek = ByteArray(32) { 6 }
        val testData = ByteArray(128 * 1024) { (it % 200).toByte() }
        val progressReports = mutableListOf<Long>()

        val streamingBody = StreamingEncryptedRequestBody(
            plaintextSize = testData.size.toLong(),
            dek = dek,
            chunkSize = 32 * 1024,
            queueCapacity = 2,
            onProgress = { sent, total ->
                progressReports.add(sent)
                assertEquals((testData.size + 28).toLong(), total)
            },
            inputStreamProvider = { ByteArrayInputStream(testData) }
        )

        val buffer = Buffer()
        streamingBody.writeTo(buffer)

        assertTrue(progressReports.size >= 4)
        assertEquals((testData.size + 28).toLong(), progressReports.last())

        val writtenBytes = buffer.readByteArray()
        val decrypted = AesGcmEnvelope.decrypt(writtenBytes, dek)
        assertArrayEquals(testData, decrypted)
    }

    @Test
    fun streamingEncryptedRequestBody_flushesEveryChunkDirectlyToSink() {
        val dek = ByteArray(32) { 9 }
        val testData = ByteArray(64 * 1024) { (it % 150).toByte() }
        val flushCount = AtomicInteger(0)

        val streamingBody = StreamingEncryptedRequestBody(
            plaintextSize = testData.size.toLong(),
            dek = dek,
            chunkSize = 16 * 1024,
            queueCapacity = 2,
            inputStreamProvider = { ByteArrayInputStream(testData) }
        )

        val rawBuffer = Buffer()
        val trackingSink = object : ForwardingSink(rawBuffer) {
            override fun flush() {
                flushCount.incrementAndGet()
                super.flush()
            }
        }.buffer()

        streamingBody.writeTo(trackingSink)

        // Must flush IV + 4 data chunks + final chunk
        assertTrue("Expected multiple direct chunk flushes, got ${flushCount.get()}", flushCount.get() >= 5)
    }
}
