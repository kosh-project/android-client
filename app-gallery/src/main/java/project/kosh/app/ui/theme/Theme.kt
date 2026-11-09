package project.kosh.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Modern dark theme color scheme for Kosh Material 3.
 */
private val DarkColorScheme = darkColorScheme(
    primary = KoshPrimaryDark,
    onPrimary = KoshOnPrimaryDark,
    primaryContainer = KoshPrimaryContainerDark,
    onPrimaryContainer = KoshOnPrimaryContainerDark,
    secondary = KoshSecondaryDark,
    onSecondary = KoshOnSecondaryDark,
    secondaryContainer = KoshSecondaryContainerDark,
    onSecondaryContainer = KoshOnSecondaryContainerDark,
    tertiary = KoshTertiaryDark,
    onTertiary = KoshOnTertiaryDark,
    tertiaryContainer = KoshTertiaryContainerDark,
    onTertiaryContainer = KoshOnTertiaryContainerDark,
    background = KoshBackgroundDark,
    onBackground = KoshOnBackgroundDark,
    surface = KoshSurfaceDark,
    onSurface = KoshOnSurfaceDark,
    surfaceVariant = KoshSurfaceVariantDark,
    onSurfaceVariant = KoshOnSurfaceVariantDark,
    outline = KoshOutlineDark,
    outlineVariant = KoshOutlineVariantDark,
    error = KoshErrorDark,
    onError = KoshOnErrorDark,
    errorContainer = KoshErrorContainerDark,
    onErrorContainer = KoshOnErrorContainerDark
)

/**
 * Modern light theme color scheme for Kosh Material 3.
 */
private val LightColorScheme = lightColorScheme(
    primary = KoshPrimaryLight,
    onPrimary = KoshOnPrimaryLight,
    primaryContainer = KoshPrimaryContainerLight,
    onPrimaryContainer = KoshOnPrimaryContainerLight,
    secondary = KoshSecondaryLight,
    onSecondary = KoshOnSecondaryLight,
    secondaryContainer = KoshSecondaryContainerLight,
    onSecondaryContainer = KoshOnSecondaryContainerLight,
    tertiary = KoshTertiaryLight,
    onTertiary = KoshOnTertiaryLight,
    tertiaryContainer = KoshTertiaryContainerLight,
    onTertiaryContainer = KoshOnTertiaryContainerLight,
    background = KoshBackgroundLight,
    onBackground = KoshOnBackgroundLight,
    surface = KoshSurfaceLight,
    onSurface = KoshOnSurfaceLight,
    surfaceVariant = KoshSurfaceVariantLight,
    onSurfaceVariant = KoshOnSurfaceVariantLight,
    outline = KoshOutlineLight,
    outlineVariant = KoshOutlineVariantLight,
    error = KoshErrorLight,
    onError = KoshOnErrorLight,
    errorContainer = KoshErrorContainerLight,
    onErrorContainer = KoshOnErrorContainerLight
)

/**
 * Application Material 3 theme configuration.
 *
 * Supports dynamic color theming on Android 12 (API level 31) and higher, falling back
 * to custom dark and light color schemes on older platform versions.
 *
 * @param darkTheme Whether to apply dark color palette defaults.
 * @param dynamicColor Whether to enable dynamic color theming on Android 12+.
 * @param content The composable hierarchy to render within the theme scope.
 */
@Composable
fun KoshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}