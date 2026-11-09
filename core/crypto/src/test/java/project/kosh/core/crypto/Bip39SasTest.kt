package project.kosh.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip39SasTest {

    @Test
    fun `deriveWords matches standard BIP-39 test vector for zero entropy`() {
        val zeroEntropy = ByteArray(16) { 0 }
        val words = Bip39Sas.deriveWords(zeroEntropy)

        assertEquals(12, words.size)
        // Checksum of 16 zero bytes has first 4 bits = 0011 (3 in decimal), which maps to "about"
        val expected = listOf(
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "about"
        )
        assertEquals(expected, words)
    }

    @Test
    fun `deriveWords produces deterministic output matching Rust pairing logic`() {
        val fingerprint = ByteArray(32) { 0xab.toByte() }
        val words = Bip39Sas.deriveWords(fingerprint)

        val expected = listOf(
            "produce", "front", "turtle", "firm",
            "rival", "still", "push", "install",
            "produce", "front", "turtle", "find"
        )
        assertEquals(expected, words)
    }

    @Test
    fun `deriveWords rejects fingerprint shorter than 16 bytes`() {
        val shortFingerprint = ByteArray(15) { 0x01 }
        assertThrows(IllegalArgumentException::class.java) {
            Bip39Sas.deriveWords(shortFingerprint)
        }
    }

    @Test
    fun `deriveRecoveryWords matches standard BIP-39 test vector for 256-bit zero entropy`() {
        val zeroEntropy = ByteArray(32) { 0 }
        val words = Bip39Sas.deriveRecoveryWords(zeroEntropy)

        assertEquals(24, words.size)
        // Checksum of 32 zero bytes has first 8 bits = 01100110 (102 in decimal), which maps to "art"
        val expected = List(23) { "abandon" } + listOf("art")
        assertEquals(expected, words)
    }

    @Test
    fun `deriveRecoveryWords rejects entropy shorter than 32 bytes`() {
        val shortEntropy = ByteArray(31) { 0 }
        assertThrows(IllegalArgumentException::class.java) {
            Bip39Sas.deriveRecoveryWords(shortEntropy)
        }
    }

    @Test
    fun `formatGrid formats 12 words into 3 lines of 4 words`() {
        val zeroEntropy = ByteArray(16) { 0 }
        val words = Bip39Sas.deriveWords(zeroEntropy)
        val grid = Bip39Sas.formatGrid(words)

        assertEquals(3, grid.size)
        assertEquals("abandon abandon abandon abandon", grid[0])
        assertEquals("abandon abandon abandon abandon", grid[1])
        assertEquals("abandon abandon abandon about", grid[2])
    }

    @Test
    fun `formatRows splits 12 words into 3 lists of 4 words`() {
        val zeroEntropy = ByteArray(16) { 0 }
        val words = Bip39Sas.deriveWords(zeroEntropy)
        val rows = Bip39Sas.formatRows(words)

        assertEquals(3, rows.size)
        assertEquals(listOf("abandon", "abandon", "abandon", "abandon"), rows[0])
        assertEquals(listOf("abandon", "abandon", "abandon", "abandon"), rows[1])
        assertEquals(listOf("abandon", "abandon", "abandon", "about"), rows[2])
    }

    @Test
    fun `formatRows24 splits 24 words into 6 lists of 4 words`() {
        val zeroEntropy = ByteArray(32) { 0 }
        val words = Bip39Sas.deriveRecoveryWords(zeroEntropy)
        val rows = Bip39Sas.formatRows24(words)

        assertEquals(6, rows.size)
        rows.subList(0, 5).forEach { row ->
            assertEquals(listOf("abandon", "abandon", "abandon", "abandon"), row)
        }
        assertEquals(listOf("abandon", "abandon", "abandon", "art"), rows[5])
    }

    @Test
    fun `isValidWord validates against canonical BIP-39 dictionary`() {
        assertTrue(Bip39Sas.isValidWord("abandon"))
        assertTrue(Bip39Sas.isValidWord("ABANDON"))
        assertTrue(Bip39Sas.isValidWord("zoo"))
        assertTrue(Bip39Sas.isValidWord("  legal  "))

        assertFalse(Bip39Sas.isValidWord("notabipword"))
        assertFalse(Bip39Sas.isValidWord(""))
        assertFalse(Bip39Sas.isValidWord("123"))
    }

    @Test
    fun `generateRecoveryPhrase generates exactly 24 valid BIP-39 words`() {
        val phrase = Bip39Sas.generateRecoveryPhrase()
        assertEquals(24, phrase.size)
        phrase.forEach { word ->
            assertTrue("Word $word should be valid BIP-39 word", Bip39Sas.isValidWord(word))
        }
    }

    @Test
    fun `isValidPhrase validates 12-word and 24-word space-separated mnemonics`() {
        val valid12Phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertTrue(Bip39Sas.isValidPhrase(valid12Phrase))
        assertTrue(Bip39Sas.isValidPhrase("  ABANDON   abandon   abandon abandon abandon abandon abandon abandon abandon abandon abandon ABOUT  "))

        val valid24Phrase = (List(23) { "abandon" } + listOf("art")).joinToString(" ")
        assertTrue(Bip39Sas.isValidPhrase(valid24Phrase))
        assertTrue(Bip39Sas.isValidRecoveryPhrase(valid24Phrase))

        // 12-word phrase should be valid for general phrase, but not recovery-specific 24-word validator
        assertFalse(Bip39Sas.isValidRecoveryPhrase(valid12Phrase))

        // Invalid count
        assertFalse(Bip39Sas.isValidPhrase("abandon abandon"))
        // Invalid word
        assertFalse(Bip39Sas.isValidPhrase("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon notaword"))
        // Empty
        assertFalse(Bip39Sas.isValidPhrase(""))
    }

    @Test
    fun `extractWords and normalizePhrase strip numbering prefixes, commas, and newlines`() {
        val numberedPhrase = """
            fuel airport foil harvest day mail soup ahead recipe vague nasty
            12. light 13. casino 14. produce 15. legal 16. zoo
            17. abandon 18. art 19. bubble 20. cactus 21. dial 22. eager 23. frame 24. galaxy
        """.trimIndent()

        val words = Bip39Sas.extractWords(numberedPhrase)
        assertEquals(24, words.size)
        assertEquals("fuel", words[0])
        assertEquals("nasty", words[10])
        assertEquals("light", words[11])
        assertEquals("casino", words[12])
        assertEquals("produce", words[13])
        assertEquals("galaxy", words[23])

        val normalized = Bip39Sas.normalizePhrase(numberedPhrase)
        assertTrue(Bip39Sas.isValidRecoveryPhrase(normalized))
    }

    @Test
    fun `extractWords handles bracketed numbers, parentheses, hash prefixes, and unicode whitespace`() {
        val messyPhrase = """
            [01] fuel  [02] airport   (3) foil  (4) harvest  #5: day  #6: mail
            [7] soup  8. ahead  9) recipe  10- vague  11. nasty
            12. light 13. casino 14. produce 15. legal 16. zoo
            17. abandon 18. art 19. bubble 20. cactus 21. dial 22. eager 23. frame 24. galaxy
        """.trimIndent()

        val words = Bip39Sas.extractWords(messyPhrase)
        assertEquals(24, words.size)
        assertEquals("fuel", words[0])
        assertEquals("airport", words[1])
        assertEquals("foil", words[2])
        assertEquals("harvest", words[3])
        assertEquals("day", words[4])
        assertEquals("mail", words[5])
        assertEquals("soup", words[6])
        assertEquals("ahead", words[7])
        assertEquals("galaxy", words[23])

        val unicodeMessy = "abandon\u00A0abandon\u2000abandon\u200Babandon\uFEFF abandon abandon abandon abandon abandon abandon abandon about"
        val unicodeWords = Bip39Sas.extractWords(unicodeMessy)
        assertEquals(12, unicodeWords.size)
        assertEquals("abandon", unicodeWords[0])
        assertEquals("about", unicodeWords[11])
    }
}
