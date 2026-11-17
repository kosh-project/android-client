package project.kosh.feature.gallery.ui

/**
 * Mathematical models and design constants governing upload veil progression
 * and reveal animations for media gallery tiles.
 *
 * ### Dual-Phase Visual Progression
 * When media files are synced to the zero-knowledge vault:
 * 1. **Phase 1 (Hashing):** A 38% black veil shrinks *upwards* towards the top edge as SHA-256
 *    chunk hashing verifies local payload integrity.
 * 2. **Phase 2 (Upload):** A 38% black rectangle rises *from the bottom edge* towards the top edge
 *    in lockstep with network transport progress.
 * 3. **Phase 3 (Completion Flash):** A smooth fade-out reveal exposes the unoccluded thumbnail
 *    and flashes a checkmark icon before settling into idle.
 */
object GalleryItemTileVisuals {
    /** Target opacity of the dual-phase black progress veil during hashing and uploading. */
    const val VEIL_ALPHA: Float = 0.38f

    /** Duration in milliseconds for the veil to fade out once upload completes. */
    const val VEIL_FADE_OUT_DURATION_MS: Int = 600

    /** Duration in milliseconds for the rising upload veil tween interpolation. */
    const val VEIL_RISE_DURATION_MS: Int = 450

    /** Duration in milliseconds for the completion badge flash animation. */
    const val COMPLETED_FLASH_DURATION_MS: Long = 800L

    /**
     * Resolves the target opacity for the upload progress veil.
     *
     * @param isUploading Whether media payload transmission is actively in progress.
     * @return 0.38f during active upload, or 0.0f when idle or completed.
     */
    fun targetVeilAlpha(isUploading: Boolean): Float =
        if (isUploading) VEIL_ALPHA else 0.0f

    /**
     * Determines whether the upload veil should preserve its full 100% height while
     * fading out, preventing premature vertical collapse.
     *
     * @param isCompleted Whether the upload stage reached completion.
     * @param animatedVeilAlpha Current interpolated alpha value of the fading veil.
     * @return True if full height must be maintained; false once alpha reaches zero.
     */
    fun shouldKeepFullHeight(isCompleted: Boolean, animatedVeilAlpha: Float): Boolean =
        isCompleted || animatedVeilAlpha > 0.01f

    /**
     * Computes the vertical height fraction (0.0 to 1.0) of the rising upload veil.
     *
     * @param isUploading Whether data transmission is active.
     * @param uploadProgress Normalized progress fraction between 0.0 and 1.0.
     * @param keepFullHeight Whether the veil is in post-completion fade-out.
     * @return Normalized height fraction clamped between 0.0 and 1.0.
     */
    fun targetVeilHeight(
        isUploading: Boolean,
        uploadProgress: Float,
        keepFullHeight: Boolean
    ): Float =
        if (keepFullHeight) 1f else if (isUploading) uploadProgress.coerceIn(0f, 1f) else 0f
}
