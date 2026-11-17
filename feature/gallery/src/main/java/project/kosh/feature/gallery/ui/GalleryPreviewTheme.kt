package project.kosh.feature.gallery.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Preview theme provider for the gallery module.
 *
 * Provides calibrated dark and light Material 3 color palettes for Android Studio
 * design previews and Robolectric UI screenshot verification. Adheres to quiet chrome
 * and high-contrast content rules.
 *
 * @param darkTheme Whether to render using the dark color palette.
 * @param content The composable tree to preview.
 */
@Composable
fun GalleryPreviewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val darkColors = darkColorScheme(
        primary = Color(0xFFD0BCFF),
        onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B),
        onPrimaryContainer = Color(0xFFEADDFF),
        surface = Color(0xFF141218),
        onSurface = Color(0xFFE6E0E9),
        surfaceVariant = Color(0xFF2B2930),
        onSurfaceVariant = Color(0xFFCAC4D0),
        outlineVariant = Color(0xFF49454F),
        background = Color(0xFF141218),
        onBackground = Color(0xFFE6E0E9),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC)
    )

    val lightColors = lightColorScheme(
        primary = Color(0xFF6750A4),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF21005D),
        surface = Color(0xFFFEF7FF),
        onSurface = Color(0xFF1D1B20),
        surfaceVariant = Color(0xFFE7E0EC),
        onSurfaceVariant = Color(0xFF49454F),
        outlineVariant = Color(0xFFCAC4D0),
        background = Color(0xFFFEF7FF),
        onBackground = Color(0xFF1D1B20),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B)
    )

    MaterialTheme(
        colorScheme = if (darkTheme) darkColors else lightColors,
        content = content
    )
}
