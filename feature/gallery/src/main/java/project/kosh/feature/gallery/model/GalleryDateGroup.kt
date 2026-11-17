package project.kosh.feature.gallery.model

import project.kosh.core.database.model.MediaWithCryptoDetails

/**
 * Precomputed date-bucketed gallery section for zero-allocation UI rendering.
 *
 * @param key Unique key for sticky header tracking (e.g. ISO-8601 date string).
 * @param title Human-readable section heading (e.g. "Today", "Yesterday", "14 Nov 2026").
 * @param items List of media assets belonging to this calendar date.
 */
data class GalleryDateGroup(
    val key: String,
    val title: String,
    val items: List<MediaWithCryptoDetails>
)
