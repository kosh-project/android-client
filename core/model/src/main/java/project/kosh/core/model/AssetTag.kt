package project.kosh.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = AssetTagSerializer::class)
enum class AssetTag(val id: Int) {
    GALLERY_META(0),
    GALLERY_ITEM(1),
    DRIVE_META(2),
    DRIVE_ITEM(3);

    companion object {
        fun fromId(id: Int): AssetTag = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown AssetTag id: $id")
    }
}

object AssetTagSerializer : KSerializer<AssetTag> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AssetTag", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: AssetTag) {
        encoder.encodeInt(value.id)
    }

    override fun deserialize(decoder: Decoder): AssetTag {
        return AssetTag.fromId(decoder.decodeInt())
    }
}