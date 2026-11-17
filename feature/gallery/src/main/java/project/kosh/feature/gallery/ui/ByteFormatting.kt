package project.kosh.feature.gallery.ui

import java.util.Locale

/**
 * Formats a raw byte quantity into a human-readable string using binary unit increments
 * (B, KB, MB, GB).
 *
 * @param bytes Raw byte count to format.
 * @return Formatted string with one decimal place of precision (e.g. "12.4 MB").
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
