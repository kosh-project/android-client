package project.kosh.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HMAC-based Extract-and-Expand Key Derivation Function (HKDF) using SHA-256.
 */
object Hkdf {
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * HKDF-Extract(salt, IKM) -> PRK
     */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(effectiveSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand(PRK, info, L) -> OKM
     */
    fun expand(prk: ByteArray, info: ByteArray, outLen: Int = 32): ByteArray {
        require(outLen in 1..8160) { "outLen out of range: $outLen" }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
        val result = ByteArray(outLen)
        var t = ByteArray(0)
        var generated = 0
        var round = 1
        while (generated < outLen) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(round.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, outLen - generated)
            System.arraycopy(t, 0, result, generated, toCopy)
            generated += toCopy
            round++
        }
        return result
    }

    /**
     * Computes complete HKDF: extract followed by expand.
     */
    fun computeHkdf(
        ikm: ByteArray,
        salt: ByteArray? = ByteArray(32),
        info: ByteArray = "kosh-crdt-ledger-v1".toByteArray(Charsets.UTF_8),
        outLen: Int = 32
    ): ByteArray {
        val prk = extract(salt, ikm)
        return expand(prk, info, outLen)
    }
}
