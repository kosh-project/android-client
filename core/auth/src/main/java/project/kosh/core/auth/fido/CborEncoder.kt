package project.kosh.core.auth.fido

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.interfaces.ECPublicKey

/**
 * Lightweight, zero-dependency CBOR (Concise Binary Object Representation, RFC 8949) encoder
 * specifically constructed for W3C WebAuthn Level 2 / FIDO2 CTAP2 cryptographic wire framing.
 *
 * This implementation enforces deterministic canonical CBOR serialization rules (RFC 8949 Section 4.2.1)
 * to ensure mathematical compatibility with backend WebAuthn verifiers (such as Rust `webauthn-rs`):
 * 1. Map keys are ordered lexicographically by their encoded binary representations (shorter lengths precede longer lengths).
 * 2. Integers are encoded in their most compact canonical binary representation.
 * 3. Byte strings and text strings use definite length headers.
 *
 * ### Supported WebAuthn Structures:
 * - COSE_Key for ES256 (NIST P-256 / secp256r1 ECDSA with SHA-256) per RFC 8152 / RFC 9053.
 * - Authenticator Data (`authData`) byte framing for both registration and authentication ceremonies.
 * - Attestation Object (`attestationObject`) CBOR map with `fmt: "none"` self-attestation.
 */
object CborEncoder {

    /**
     * Flags byte indicating User Present (UP).
     * Bit 0 (0x01): The authenticator confirmed user presence (touch, biometric, or interaction).
     */
    const val FLAG_USER_PRESENT: Byte = 0x01

    /**
     * Flags byte indicating User Verified (UV).
     * Bit 2 (0x04): The authenticator verified the user via biometrics (fingerprint/face) or PIN.
     */
    const val FLAG_USER_VERIFIED: Byte = 0x04

    /**
     * Flags byte indicating Attested Credential Data Present (AT).
     * Bit 6 (0x40): Authenticator data includes the AAGUID, credential ID, and public key.
     */
    const val FLAG_ATTESTED_CREDENTIAL_DATA: Byte = 0x40

    /**
     * Combined flags byte for standard biometric passkey registration ceremonies.
     * Binary: `0100 0101` (0x45) -> User Present (0x01) + User Verified (0x04) + Attested Credential Data (0x40).
     */
    const val FLAGS_REGISTRATION_BIOMETRIC: Byte = (0x01 or 0x04 or 0x40).toByte()

    /**
     * Combined flags byte for standard biometric passkey assertion (login) ceremonies.
     * Binary: `0000 0101` (0x05) -> User Present (0x01) + User Verified (0x04).
     */
    const val FLAGS_ASSERTION_BIOMETRIC: Byte = (0x01 or 0x04).toByte()

    /**
     * Encodes a NIST P-256 ([ECPublicKey]) into a standard COSE_Key CBOR map for ES256 (alg: -7).
     *
     * Wire layout of the resulting 77-byte canonical CBOR map:
     * ```text
     * Offset | Value        | Field Description
     * -------+--------------+-----------------------------------------------------------
     * 00     | 0xa5         | CBOR Map of 5 entries
     * 01..02 | 0x01, 0x02   | Key 1 (kty) -> Value 2 (EC2 key type)
     * 03..04 | 0x03, 0x26   | Key 3 (alg) -> Value -7 (ES256 signature algorithm)
     * 05..06 | 0x20, 0x01   | Key -1 (crv) -> Value 1 (NIST P-256 / secp256r1 curve)
     * 07..09 | 0x21, 0x58,  | Key -2 (x) -> Byte string (0x58) of length 32 (0x20)
     *        | 0x20         |
     * 10..41 | [32 bytes]   | Uncompressed X affine coordinate (big-endian, zero-padded)
     * 42..44 | 0x22, 0x58,  | Key -3 (y) -> Byte string (0x58) of length 32 (0x20)
     *        | 0x20         |
     * 45..76 | [32 bytes]   | Uncompressed Y affine coordinate (big-endian, zero-padded)
     * ```
     *
     * In accordance with RFC 8949 canonical CBOR ordering:
     * - Key 1: encoded byte `0x01` (length 1)
     * - Key 3: encoded byte `0x03` (length 1)
     * - Key -1: encoded byte `0x20` (length 1)
     * - Key -2: encoded byte `0x21` (length 1)
     * - Key -3: encoded byte `0x22` (length 1)
     * All keys are 1-byte integers sorted in ascending lexicographical order (`0x01 < 0x03 < 0x20 < 0x21 < 0x22`).
     *
     * @param ecPublicKey The P-256 public key generated inside the hardware enclave.
     * @return 77-byte canonical CBOR byte array.
     */
    fun encodeCoseKey(ecPublicKey: ECPublicKey): ByteArray {
        val xBytes = ecPublicKey.w.affineX.toUnsigned32ByteArray()
        val yBytes = ecPublicKey.w.affineY.toUnsigned32ByteArray()

        val output = ByteArrayOutputStream(77)
        // 0xa5: Map of 5 key-value pairs
        output.write(0xa5)

        // Entry 1: 1 (kty) -> 2 (EC2)
        output.write(0x01)
        output.write(0x02)

        // Entry 2: 3 (alg) -> -7 (ES256) (major type 1, negative integer: -1 - 6 = -7 -> 0x26)
        output.write(0x03)
        output.write(0x26)

        // Entry 3: -1 (crv) -> 1 (P-256) (major type 1, negative integer: -1 - 0 = -1 -> 0x20)
        output.write(0x20)
        output.write(0x01)

        // Entry 4: -2 (x) -> 32-byte byte string (0x21, 0x58, 0x20, [32 bytes])
        output.write(0x21)
        output.write(0x58)
        output.write(0x20)
        output.write(xBytes)

        // Entry 5: -3 (y) -> 32-byte byte string (0x22, 0x58, 0x20, [32 bytes])
        output.write(0x22)
        output.write(0x58)
        output.write(0x20)
        output.write(yBytes)

        return output.toByteArray()
    }

    /**
     * Constructs the WebAuthn Authenticator Data (`authData`) byte array.
     *
     * Wire layout (W3C WebAuthn Level 2 Section 6.1):
     * ```text
     * Offset | Length | Description
     * -------+--------+-----------------------------------------------------------
     * 00..31 | 32 B   | SHA-256 hash of the Relying Party ID (rpIdHash)
     * 32     | 1 B    | Bit flags (UP=1, UV=1, AT=1, etc.)
     * 33..36 | 4 B    | Sign counter (32-bit big-endian unsigned integer)
     * ------- Optional Attested Credential Data (present if flag AT (0x40) is set) -------
     * 37..52 | 16 B   | AAGUID (Authenticator Attestation Globally Unique Identifier)
     * 53..54 | 2 B    | Credential ID length (L, 16-bit big-endian unsigned integer)
     * 55..54+L| L B   | Credential ID byte sequence
     * 55+L.. | K B    | Credential Public Key in COSE_Key format (77 bytes for ES256)
     * ```
     *
     * @param rpIdHash 32-byte SHA-256 digest of the Relying Party identifier string.
     * @param flags Bit flags byte representing authenticator status.
     * @param signCount Incrementing signature counter to detect cloned authenticators.
     * @param aaguid Optional 16-byte AAGUID (defaults to all zeros for self/internal authenticators).
     * @param credentialId Optional raw byte identifier for the newly created credential.
     * @param coseKey Optional COSE_Key bytes representing the public key.
     * @return Raw concatenated authenticator data bytes.
     */
    fun encodeAuthData(
        rpIdHash: ByteArray,
        flags: Byte,
        signCount: Int,
        aaguid: ByteArray? = null,
        credentialId: ByteArray? = null,
        coseKey: ByteArray? = null
    ): ByteArray {
        require(rpIdHash.size == 32) { "rpIdHash must be exactly 32 bytes, got ${rpIdHash.size}" }

        val hasAttestedData = (flags.toInt() and FLAG_ATTESTED_CREDENTIAL_DATA.toInt()) != 0
        val credId = credentialId ?: ByteArray(0)
        val key = coseKey ?: ByteArray(0)
        val guid = aaguid ?: ByteArray(16)

        val estimatedSize = 37 + if (hasAttestedData) 16 + 2 + credId.size + key.size else 0
        val output = ByteArrayOutputStream(estimatedSize)

        // 1. rpIdHash (32 bytes)
        output.write(rpIdHash)

        // 2. flags (1 byte)
        output.write(flags.toInt() and 0xff)

        // 3. signCount (4 bytes, big-endian)
        output.write((signCount ushr 24) and 0xff)
        output.write((signCount ushr 16) and 0xff)
        output.write((signCount ushr 8) and 0xff)
        output.write(signCount and 0xff)

        // 4. Attested Credential Data (if AT flag is set)
        if (hasAttestedData) {
            require(guid.size == 16) { "AAGUID must be exactly 16 bytes, got ${guid.size}" }
            require(credId.isNotEmpty()) { "Credential ID must not be empty when AT flag is set" }
            require(key.isNotEmpty()) { "COSE Key must not be empty when AT flag is set" }

            output.write(guid)

            // Credential ID length (2 bytes, big-endian unsigned integer)
            output.write((credId.size ushr 8) and 0xff)
            output.write(credId.size and 0xff)

            // Credential ID
            output.write(credId)

            // COSE_Key
            output.write(key)
        }

        return output.toByteArray()
    }

    /**
     * Constructs the complete WebAuthn `attestationObject` CBOR map with `fmt: "none"`.
     *
     * WebAuthn Level 2 Section 6.5.1:
     * Represents self-attestation where no third-party certificate authority chain is provided.
     * The attestation map contains:
     * ```json
     * {
     *   "fmt": "none",
     *   "attStmt": {},
     *   "authData": <byte string of authData>
     * }
     * ```
     *
     * Canonical CBOR key sorting (shorter key length precedes longer key length):
     * 1. `"fmt"`: text string of length 3 (`0x63, 0x66, 0x6d, 0x74`) -> `"none"` (`0x64, 0x6e, 0x6f, 0x6e, 0x65`)
     * 2. `"attStmt"`: text string of length 7 (`0x67, 'a', 't', 't', 'S', 't', 'm', 't'`) -> `{}` (`0xa0`)
     * 3. `"authData"`: text string of length 8 (`0x68, 'a', 'u', 't', 'h', 'D', 'a', 't', 'a'`) -> `<bytes>`
     *
     * @param rpIdHash 32-byte SHA-256 digest of the Relying Party identifier string.
     * @param credentialId Unique byte array identifying the credential.
     * @param ecPublicKey The enclave-generated P-256 public key.
     * @param signCount Initial signature counter (defaults to 0 for registration).
     * @param aaguid Optional 16-byte AAGUID (defaults to all zeros).
     * @return Canonical CBOR encoded byte array for the `attestationObject`.
     */
    fun encodeAttestationObject(
        rpIdHash: ByteArray,
        credentialId: ByteArray,
        ecPublicKey: ECPublicKey,
        signCount: Int = 0,
        aaguid: ByteArray = ByteArray(16)
    ): ByteArray {
        val coseKey = encodeCoseKey(ecPublicKey)
        val authData = encodeAuthData(
            rpIdHash = rpIdHash,
            flags = FLAGS_REGISTRATION_BIOMETRIC,
            signCount = signCount,
            aaguid = aaguid,
            credentialId = credentialId,
            coseKey = coseKey
        )

        val output = ByteArrayOutputStream(authData.size + 32)

        // 0xa3: Map of 3 key-value pairs
        output.write(0xa3)

        // Entry 1: "fmt" (len 3) -> "none" (len 4)
        output.write(0x63)
        output.write("fmt".toByteArray(Charsets.UTF_8))
        output.write(0x64)
        output.write("none".toByteArray(Charsets.UTF_8))

        // Entry 2: "attStmt" (len 7) -> {} (empty map 0xa0)
        output.write(0x67)
        output.write("attStmt".toByteArray(Charsets.UTF_8))
        output.write(0xa0)

        // Entry 3: "authData" (len 8) -> byte string
        output.write(0x68)
        output.write("authData".toByteArray(Charsets.UTF_8))

        // Byte string header for authData
        encodeByteStringHeader(authData.size, output)
        output.write(authData)

        return output.toByteArray()
    }

    /**
     * Writes the CBOR major type 2 (byte string) length header.
     */
    private fun encodeByteStringHeader(size: Int, output: ByteArrayOutputStream) {
        when {
            size <= 23 -> {
                output.write(0x40 or size)
            }
            size <= 0xff -> {
                output.write(0x58)
                output.write(size)
            }
            size <= 0xffff -> {
                output.write(0x59)
                output.write((size ushr 8) and 0xff)
                output.write(size and 0xff)
            }
            else -> {
                output.write(0x5a)
                output.write((size ushr 24) and 0xff)
                output.write((size ushr 16) and 0xff)
                output.write((size ushr 8) and 0xff)
                output.write(size and 0xff)
            }
        }
    }

    /**
     * Normalizes a [BigInteger] coordinate into an exact 32-byte unsigned big-endian array.
     *
     * Handles sign-bit extensions (where [BigInteger.toByteArray] returns 33 bytes with a leading 0x00)
     * and high-order zero truncation (where [BigInteger.toByteArray] returns fewer than 32 bytes).
     */
    private fun BigInteger.toUnsigned32ByteArray(): ByteArray {
        val raw = this.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).apply {
                System.arraycopy(raw, 0, this, 32 - raw.size, raw.size)
            }
        }
    }
}
