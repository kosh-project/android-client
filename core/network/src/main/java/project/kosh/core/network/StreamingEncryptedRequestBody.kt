package project.kosh.core.network

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import project.kosh.core.crypto.AesGcmEnvelope
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Custom OkHttp [RequestBody] providing on-the-fly streaming AES-256-GCM encryption
 * directly from an [InputStream] into Okio's [BufferedSink] via a bounded producer-consumer channel.
 *
 * Wire format: [12-byte IV][ciphertext][16-byte GCM tag].
 * Total encrypted size is exactly declared up-front:
 * `plaintextSize + IV_SIZE_BYTES (12) + TAG_SIZE_BYTES (16) = plaintextSize + 28`.
 *
 * Concurrency architecture:
 * - Producer: Runs in a dedicated background worker thread, reads plaintext in [chunkSize] (default 64 KiB)
 *   chunks, encrypts via AES-256-GCM cipher.update(), and enqueues encrypted byte chunks into a bounded
 *   [ArrayBlockingQueue]. When the queue is full (capacity [queueCapacity], default 8 chunks = 512 KiB ceiling),
 *   the producer pauses via natural backpressure, strictly bounding heap allocation to ~512 KiB regardless
 *   of media payload size (e.g. 355 MB or 4 GB).
 * - Consumer: Runs synchronously in [writeTo] on OkHttp's network execution thread, dequeues encrypted chunks,
 *   writes to [sink], updates upload progress, and immediately flushes ([sink.flush()]), streaming chunks
 *   directly to the TLS socket and draining Okio's internal segment buffer.
 */
class StreamingEncryptedRequestBody(
    private val plaintextSize: Long,
    private val dek: ByteArray,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    private val inputStreamProvider: () -> InputStream?
) : RequestBody() {

    constructor(
        plaintextSize: Long,
        dek: ByteArray,
        inputStreamProvider: () -> InputStream?
    ) : this(
        plaintextSize = plaintextSize,
        dek = dek,
        chunkSize = DEFAULT_CHUNK_SIZE,
        queueCapacity = DEFAULT_QUEUE_CAPACITY,
        onProgress = null,
        inputStreamProvider = inputStreamProvider
    )

    constructor(
        plaintextSize: Long,
        dek: ByteArray,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?,
        inputStreamProvider: () -> InputStream?
    ) : this(
        plaintextSize = plaintextSize,
        dek = dek,
        chunkSize = DEFAULT_CHUNK_SIZE,
        queueCapacity = DEFAULT_QUEUE_CAPACITY,
        onProgress = onProgress,
        inputStreamProvider = inputStreamProvider
    )

    init {
        require(chunkSize > 0) { "chunkSize must be greater than 0, got $chunkSize" }
        require(queueCapacity > 0) { "queueCapacity must be greater than 0, got $queueCapacity" }
    }

    override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()

    override fun contentLength(): Long {
        if (plaintextSize < 0L) return -1L
        return plaintextSize + AesGcmEnvelope.IV_SIZE_BYTES + AesGcmEnvelope.TAG_SIZE_BYTES
    }

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val stream = inputStreamProvider()
            ?: throw IOException("Could not open input stream for streaming encrypted upload")

        val queue: BlockingQueue<EncryptedChunk> = ArrayBlockingQueue(queueCapacity)
        val producerError = AtomicReference<Throwable?>(null)
        val consumerStopped = AtomicBoolean(false)
        val totalBytes = contentLength()
        var totalBytesWritten = 0L

        val producerThread = Thread({
            try {
                stream.use { input ->
                    val iv = AesGcmEnvelope.generateIv()
                    queue.put(EncryptedChunk.Data(iv))

                    val cipher = AesGcmEnvelope.createEncryptCipher(dek, iv)
                    val buffer = ByteArray(chunkSize)
                    var bytesRead = 0
                    var totalPlaintextRead = 0L

                    while (!consumerStopped.get() && input.read(buffer).also { bytesRead = it } != -1) {
                        if (bytesRead > 0) {
                            totalPlaintextRead += bytesRead
                            val ciphertext = cipher.update(buffer, 0, bytesRead)
                            if (ciphertext != null && ciphertext.isNotEmpty()) {
                                queue.put(EncryptedChunk.Data(ciphertext))
                            }
                        }
                    }

                    if (!consumerStopped.get()) {
                        if (plaintextSize >= 0L && totalPlaintextRead != plaintextSize) {
                            throw IOException(
                                "Plaintext stream size mismatch: expected $plaintextSize bytes, read $totalPlaintextRead bytes"
                            )
                        }

                        val finalBytes = cipher.doFinal()
                        if (finalBytes != null && finalBytes.isNotEmpty()) {
                            queue.put(EncryptedChunk.Data(finalBytes))
                        }
                        queue.put(EncryptedChunk.Done)
                    }
                }
            } catch (e: InterruptedException) {
                // Thread interrupted on consumer abort or cancellation
            } catch (t: Throwable) {
                producerError.set(t)
                queue.clear()
                if (!queue.offer(EncryptedChunk.Error(t))) {
                    try {
                        queue.put(EncryptedChunk.Error(t))
                    } catch (_: InterruptedException) {}
                }
            }
        }, "kosh-encrypt-producer")

        producerThread.isDaemon = true
        producerThread.start()

        try {
            while (true) {
                val chunk = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    if (!producerThread.isAlive) {
                        val err = producerError.get()
                        if (err is IOException) throw err
                        if (err != null) throw IOException("Streaming encryption failed", err)
                        throw IOException("Streaming encryption producer terminated unexpectedly")
                    }
                    continue
                }
                when (chunk) {
                    is EncryptedChunk.Data -> {
                        sink.write(chunk.bytes)
                        sink.flush()
                        totalBytesWritten += chunk.bytes.size
                        onProgress?.invoke(totalBytesWritten, totalBytes)
                    }
                    is EncryptedChunk.Done -> {
                        sink.flush()
                        break
                    }
                    is EncryptedChunk.Error -> {
                        val cause = chunk.cause
                        if (cause is IOException) throw cause
                        throw IOException("Streaming encryption failed", cause)
                    }
                }
            }
        } catch (t: Throwable) {
            consumerStopped.set(true)
            producerThread.interrupt()
            if (t is InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Streaming upload interrupted").apply { initCause(t) }
            }
            if (t is IOException) throw t
            throw IOException("Streaming upload failed", t)
        } finally {
            consumerStopped.set(true)
            producerThread.interrupt()
            try {
                stream.close()
            } catch (_: Exception) {}
            producerThread.join(1000)
        }

        producerError.get()?.let { err ->
            if (err is IOException) throw err
            throw IOException("Streaming encryption failed", err)
        }
    }

    private sealed class EncryptedChunk {
        class Data(val bytes: ByteArray) : EncryptedChunk()
        class Error(val cause: Throwable) : EncryptedChunk()
        object Done : EncryptedChunk()
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KiB
        const val DEFAULT_QUEUE_CAPACITY = 8 // 8 * 64 KiB = 512 KiB bounded memory ceiling

        fun resolvePlaintextSize(contentResolver: ContentResolver, uri: Uri, fallback: Long = -1L): Long {
            var size = fallback
            try {
                contentResolver.query(uri, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && cursor.moveToFirst()) {
                        val s = cursor.getLong(sizeIndex)
                        if (s > 0L) size = s
                    }
                }
            } catch (_: Exception) {}
            if (size <= 0L) {
                try {
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        if (pfd.statSize > 0L) size = pfd.statSize
                    }
                } catch (_: Exception) {}
            }
            if (size <= 0L && uri.path != null) {
                try {
                    val f = java.io.File(uri.path!!)
                    if (f.exists() && f.length() > 0L) size = f.length()
                } catch (_: Exception) {}
            }
            return size
        }

        fun fromUri(
            contentResolver: ContentResolver,
            uri: Uri,
            dek: ByteArray,
            plaintextSize: Long,
            chunkSize: Int = DEFAULT_CHUNK_SIZE,
            queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
            onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
        ): StreamingEncryptedRequestBody {
            val resolvedSize = if (plaintextSize > 0L) {
                plaintextSize
            } else {
                resolvePlaintextSize(contentResolver, uri, plaintextSize)
            }
            return StreamingEncryptedRequestBody(
                plaintextSize = resolvedSize,
                dek = dek,
                chunkSize = chunkSize,
                queueCapacity = queueCapacity,
                onProgress = onProgress,
                inputStreamProvider = { contentResolver.openInputStream(uri) }
            )
        }
    }
}
