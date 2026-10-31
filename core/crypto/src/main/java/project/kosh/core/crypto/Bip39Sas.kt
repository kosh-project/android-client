package project.kosh.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Short Authentication String (SAS) word generator for Trust-On-First-Use (TOFU) device pairing
 * and BIP-39 mnemonic recovery phrase generator for Zero-Knowledge account recovery.
 *
 * Strict Separation:
 * - SAS Pairing Words: Exactly 12 BIP-39 mnemonic words derived from 16 bytes (128 bits entropy + 4-bit checksum)
 *   of the server TLS certificate SHA-256 fingerprint ([deriveWords]). Used strictly for out-of-band TOFU verification.
 * - Account Recovery Phrase: Exactly 24 BIP-39 mnemonic words derived from 32 bytes (256 bits entropy + 8-bit checksum)
 *   of cryptographically secure random entropy ([generateRecoveryPhrase], [deriveRecoveryWords]). Used for
 *   Zero-Knowledge Master Vault Key wrapping and permanent cold storage recovery.
 */
object Bip39Sas {

    const val ENTROPY_BYTES = 16
    const val WORD_COUNT = 12
    const val BITS_PER_WORD = 11

    const val SAS_ENTROPY_BYTES = 16
    const val SAS_WORD_COUNT = 12

    const val RECOVERY_ENTROPY_BYTES = 32
    const val RECOVERY_WORD_COUNT = 24

    /**
     * Generates a random 24-word BIP-39 recovery phrase from 256 bits of cryptographically secure entropy.
     *
     * @param random Cryptographically secure random number generator.
     * @return Ordered list of exactly 24 BIP-39 mnemonic words.
     */
    fun generateRecoveryPhrase(random: SecureRandom = SecureRandom()): List<String> {
        val entropy = ByteArray(RECOVERY_ENTROPY_BYTES)
        random.nextBytes(entropy)
        return deriveRecoveryWords(entropy)
    }

    /**
     * Derives 24 BIP-39 mnemonic words from 32 bytes (256 bits) of entropy and an 8-bit SHA-256 checksum.
     *
     * ### Algorithm (BIP-39 Specification for 256 bits)
     * 1. Takes the first 32 bytes of entropy ($ENT = 256$ bits).
     * 2. Computes the SHA-256 hash of these 32 bytes: $H = \text{SHA-256}(ENT)$.
     * 3. Extracts the first $CS = ENT / 32 = 8$ bits of $H$ (the first byte) as the checksum.
     * 4. Concatenates $ENT$ (256 bits) and $CS$ (8 bits) to form a 264-bit bitstring.
     * 5. Divides the 264 bits into 24 consecutive 11-bit chunks ($264 / 11 = 24$).
     * 6. Maps each 11-bit integer ($0 \le i \le 2047$) to its corresponding English word in [Bip39WordList].
     *
     * @param entropy Raw entropy bytes. Must contain at least 32 bytes.
     * @return Ordered list of exactly 24 BIP-39 English words.
     * @throws IllegalArgumentException If [entropy] contains fewer than 32 bytes.
     */
    fun deriveRecoveryWords(entropy: ByteArray): List<String> {
        require(entropy.size >= RECOVERY_ENTROPY_BYTES) {
            "Recovery entropy too short: expected at least $RECOVERY_ENTROPY_BYTES bytes, got ${entropy.size}"
        }

        val ent = entropy.copyOfRange(0, RECOVERY_ENTROPY_BYTES)
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(ent)
        val checksumByte = hash[0]

        // 264 bits = 256 entropy bits + 8 checksum bits
        val bits = BooleanArray(264)
        for (i in 0 until RECOVERY_ENTROPY_BYTES) {
            val b = ent[i].toInt()
            for (j in 0..7) {
                bits[i * 8 + j] = ((b ushr (7 - j)) and 1) == 1
            }
        }
        for (j in 0..7) {
            bits[256 + j] = ((checksumByte.toInt() ushr (7 - j)) and 1) == 1
        }

        val words = ArrayList<String>(RECOVERY_WORD_COUNT)
        for (i in 0 until RECOVERY_WORD_COUNT) {
            var index = 0
            for (j in 0 until BITS_PER_WORD) {
                if (bits[i * BITS_PER_WORD + j]) {
                    index = index or (1 shl (10 - j))
                }
            }
            words.add(Bip39WordList.WORDS[index])
        }

        return words
    }

    /**
     * Validates whether a candidate recovery phrase consists of canonical BIP-39 words.
     * Accepts either standard 24-word recovery phrases or 12-word phrases.
     *
     * @param phrase Candidate space-separated recovery phrase.
     * @return True if the phrase contains valid BIP-39 words of length 24 or 12.
     */
    fun isValidPhrase(phrase: String): Boolean {
        val words = phrase.trim().lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.size != RECOVERY_WORD_COUNT && words.size != SAS_WORD_COUNT) return false
        return words.all { isValidWord(it) }
    }

    /**
     * Specifically validates whether a recovery phrase consists of exactly 24 valid BIP-39 words.
     *
     * @param phrase Candidate space-separated recovery phrase.
     * @return True if the phrase contains exactly 24 canonical BIP-39 words.
     */
    fun isValidRecoveryPhrase(phrase: String): Boolean {
        val words = phrase.trim().lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.size != RECOVERY_WORD_COUNT) return false
        return words.all { isValidWord(it) }
    }

    /**
     * Derives 12 BIP-39 verification words from a certificate fingerprint.
     *
     * @param fingerprintBytes SHA-256 certificate fingerprint. Must contain at least 16 bytes.
     * @return Ordered list of exactly 12 BIP-39 English words.
     * @throws IllegalArgumentException If [fingerprintBytes] contains fewer than 16 bytes.
     */
    fun deriveWords(fingerprintBytes: ByteArray): List<String> {
        require(fingerprintBytes.size >= ENTROPY_BYTES) {
            "Certificate fingerprint too short: expected at least $ENTROPY_BYTES bytes, got ${fingerprintBytes.size}"
        }

        val entropy = fingerprintBytes.copyOfRange(0, ENTROPY_BYTES)
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(entropy)
        val checksumByte = hash[0]

        // 132 bits = 128 entropy bits + 4 checksum bits
        val bits = BooleanArray(132)
        for (i in 0 until ENTROPY_BYTES) {
            val b = entropy[i].toInt()
            for (j in 0..7) {
                bits[i * 8 + j] = ((b ushr (7 - j)) and 1) == 1
            }
        }
        for (j in 0..3) {
            bits[128 + j] = ((checksumByte.toInt() ushr (7 - j)) and 1) == 1
        }

        val words = ArrayList<String>(WORD_COUNT)
        for (i in 0 until WORD_COUNT) {
            var index = 0
            for (j in 0 until BITS_PER_WORD) {
                if (bits[i * BITS_PER_WORD + j]) {
                    index = index or (1 shl (10 - j))
                }
            }
            words.add(Bip39WordList.WORDS[index])
        }

        return words
    }

    /**
     * Formats 12 BIP-39 words into 3 lines of 4 words each, matching the CLI pairing overlay.
     *
     * @param words List of 12 words derived via [deriveWords].
     * @return List of 3 strings, each containing 4 words separated by spaces.
     */
    fun formatGrid(words: List<String>): List<String> {
        require(words.size == WORD_COUNT) { "Expected exactly $WORD_COUNT words, got ${words.size}" }
        val lines = ArrayList<String>(3)
        for (i in 0 until WORD_COUNT step 4) {
            lines.add(words.subList(i, i + 4).joinToString(" "))
        }
        return lines
    }

    /**
     * Splits 12 BIP-39 words into 3 rows of 4 words each for structured grid rendering.
     *
     * @param words List of 12 words derived via [deriveWords].
     * @return List of 3 sublists, each containing 4 words.
     */
    fun formatRows(words: List<String>): List<List<String>> {
        require(words.size == WORD_COUNT) { "Expected exactly $WORD_COUNT words, got ${words.size}" }
        val rows = ArrayList<List<String>>(3)
        for (i in 0 until WORD_COUNT step 4) {
            rows.add(words.subList(i, i + 4))
        }
        return rows
    }

    /**
     * Splits 24 BIP-39 words into 6 rows of 4 words each for structured grid rendering.
     *
     * @param words List of 24 words derived via [generateRecoveryPhrase] or [deriveRecoveryWords].
     * @return List of 6 sublists, each containing 4 words.
     */
    fun formatRows24(words: List<String>): List<List<String>> {
        require(words.size == RECOVERY_WORD_COUNT) { "Expected exactly $RECOVERY_WORD_COUNT words, got ${words.size}" }
        val rows = ArrayList<List<String>>(6)
        for (i in 0 until RECOVERY_WORD_COUNT step 4) {
            rows.add(words.subList(i, i + 4))
        }
        return rows
    }

    /**
     * Checks whether a given word exists in the standard 2048-word English BIP-39 dictionary.
     *
     * @param word Candidate word (case-insensitive).
     * @return True if word is in the canonical BIP-39 dictionary.
     */
    fun isValidWord(word: String): Boolean {
        return Bip39WordList.WORDS.binarySearch(word.trim().lowercase()) >= 0
    }
}
