package project.kosh.core.crypto

/**
 * Cryptographic operations for the Delta-CRDT sync ledger.
 *
 * Derives $K_{ledger}$ from the Master Vault Key via HKDF-SHA256, and encrypts/decrypts
 * wire-format Protocol Buffers action bundles using AES-256-GCM authenticated envelopes.
 */
object LedgerCrypto {
    const val INFO_STRING = "kosh-crdt-ledger-v1"

    /**
     * Derives the 32-byte $K_{ledger}$ symmetric key from [masterVaultKey].
     */
    fun deriveLedgerKey(masterVaultKey: MasterVaultKey): ByteArray {
        return Hkdf.computeHkdf(
            ikm = masterVaultKey.keyBytes,
            salt = ByteArray(32),
            info = INFO_STRING.toByteArray(Charsets.UTF_8),
            outLen = 32
        )
    }

    /**
     * Encrypts a Protobuf bundle payload using $K_{ledger}$.
     * Wire layout: [12-byte IV][ciphertext + 16-byte GCM tag]
     */
    fun encryptBundle(bundleBytes: ByteArray, ledgerKey: ByteArray): ByteArray {
        return AesGcmEnvelope.encrypt(bundleBytes, ledgerKey)
    }

    /**
     * Decrypts an encrypted bundle payload using $K_{ledger}$.
     */
    fun decryptBundle(ciphertext: ByteArray, ledgerKey: ByteArray): ByteArray {
        return AesGcmEnvelope.decrypt(ciphertext, ledgerKey)
    }
}
