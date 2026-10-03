package project.kosh.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Categorizes binary assets stored in the Kosh Content-Addressable Storage (CAS) vault.
 *
 * The backend server models asset storage tiers using a 32-bit signed integer representation
 * (`#[repr(i32)]` in Rust). Each asset uploaded via `POST /api/v1/upload/{tag}` must declare its
 * corresponding tag ID in the URL path.
 *
 * Assets are partitioned into two principal domains:
 * - **Gallery:** Mobile media photos and videos, accompanied by encrypted database snapshot metadata.
 * - **Drive:** Arbitrary user documents and file trees, accompanied by file tree metadata snapshots.
 *
 * @property id The 32-bit integer identifier matching the backend enum ordinal.
 */
@Serializable(with = AssetTagSerializer::class)
enum class AssetTag(val id: Int) {
    /**
     * Encrypted gallery metadata snapshots, including delta logs, Room SQLite backups, and index catalogs.
     */
    GALLERY_META(0),

    /**
     * Primary gallery binary media items (such as encrypted JPEG, PNG, or MP4 payloads).
     */
    GALLERY_ITEM(1),

    /**
     * Encrypted document drive metadata snapshots, directory manifests, and file hierarchy trees.
     */
    DRIVE_META(2),

    /**
     * Primary document drive binary files (such as encrypted PDFs, documents, and archives).
     */
    DRIVE_ITEM(3);

    companion object {
        /**
         * Resolves an integer identifier to its corresponding [AssetTag].
         *
         * @param id The integer ID received from the wire or storage index.
         * @return The matching [AssetTag] instance.
         * @throws IllegalArgumentException If [id] does not match any recognized tag identifier.
         */
        fun fromId(id: Int): AssetTag = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown AssetTag id: $id")
    }
}

/**
 * Custom Kotlinx Serialization serializer for [AssetTag].
 *
 * Default enum serialization in Kotlinx Serialization encodes enums as string names
 * (for example, `"GALLERY_ITEM"`). However, the Kosh backend wire protocol expects
 * integers matching the Rust `serde_repr` specification (`0..3`).
 *
 * This serializer bridges that gap by encoding and decoding tags directly as primitive integers.
 */
object AssetTagSerializer : KSerializer<AssetTag> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AssetTag", PrimitiveKind.INT)

    /**
     * Serializes the [AssetTag] as its primitive integer identifier [AssetTag.id].
     *
     * @param encoder The outgoing serialization encoder.
     * @param value The [AssetTag] instance to serialize.
     */
    override fun serialize(encoder: Encoder, value: AssetTag) {
        encoder.encodeInt(value.id)
    }

    /**
     * Deserializes a primitive integer from the incoming stream into the corresponding [AssetTag].
     *
     * @param decoder The incoming serialization decoder.
     * @return The resolved [AssetTag].
     * @throws IllegalArgumentException If the decoded integer does not match any known [AssetTag.id].
     */
    override fun deserialize(decoder: Decoder): AssetTag {
        return AssetTag.fromId(decoder.decodeInt())
    }
}