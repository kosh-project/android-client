package project.kosh.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class MediaHasherTest {

    @Test
    fun computeSha256_emptyBytes_matchesKnownTestVector() {
        val empty = ByteArray(0)
        val hash = MediaHasher.computeSha256(empty)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)

        val streamHash = MediaHasher.computeSha256(ByteArrayInputStream(empty))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", streamHash)
    }

    @Test
    fun computeSha256_helloWorld_matchesKnownTestVector() {
        val bytes = "hello world".toByteArray(Charsets.UTF_8)
        val hash = MediaHasher.computeSha256(bytes)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)

        val streamHash = MediaHasher.computeSha256(ByteArrayInputStream(bytes))
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", streamHash)
    }

    @Test
    fun computeSha256_multiChunkStreaming_matchesByteArray() {
        // Create 200 KiB test buffer (spanning multiple 64 KiB chunks)
        val large = ByteArray(200 * 1024) { (it % 251).toByte() }
        val byteHash = MediaHasher.computeSha256(large)
        val streamHash = MediaHasher.computeSha256(ByteArrayInputStream(large))
        assertEquals(byteHash, streamHash)
    }
}
