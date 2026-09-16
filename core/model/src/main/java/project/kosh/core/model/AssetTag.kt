package project.kosh.core.model

enum class AssetTag(val id: Byte) {
    GALLERY_META(0),
    GALLERY_ITEM(1),
    DRIVE_META(2),
    DRIVE_ITEM(3),
}