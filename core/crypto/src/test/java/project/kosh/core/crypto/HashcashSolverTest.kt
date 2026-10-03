package project.kosh.core.crypto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class HashcashSolverTest {

    private val solver = HashcashSolver()

    @Test
    fun solve_loginChallenge_produces_165_byteValidHeader() = runBlocking {
        val identityHash = "a".repeat(64)
        val challenge = "000000006745a1b0" + "f".repeat(64) + "login"

        val header = solver.solve(identityHash, challenge)

        assertEquals(165, header.length)
        assertTrue(header.startsWith(identityHash))
        assertTrue(header.endsWith("login"))

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(header.toByteArray(Charsets.US_ASCII))

        assertEquals(0.toByte(), digest[0])
        assertEquals(0.toByte(), digest[1])
    }

    @Test
    fun solve_registerChallenge_produces_168_byteValidHeader() = runBlocking {
        val identityHash = "b".repeat(64)
        val challenge = "000000006745a1b0" + "e".repeat(64) + "register"

        val header = solver.solve(identityHash, challenge)

        assertEquals(168, header.length)
        assertTrue(header.startsWith(identityHash))
        assertTrue(header.endsWith("register"))

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(header.toByteArray(Charsets.US_ASCII))

        assertEquals(0.toByte(), digest[0])
        assertEquals(0.toByte(), digest[1])
    }
}
