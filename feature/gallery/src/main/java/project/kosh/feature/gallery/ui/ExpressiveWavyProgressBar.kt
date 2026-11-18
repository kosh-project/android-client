package project.kosh.feature.gallery.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Continuous wavy progress indicator drawn directly on [Canvas] using quadratic Bezier splines.
 *
 * Bypasses Material 3 Expressive internal animation job drop mechanism and 1.0f clamping ceiling,
 * delivering rock-solid, deterministic wave amplitude that tracks touch gestures and overstretch
 * scaling up to 1.5x with zero frame drops.
 *
 * ### Architectural Guarantees
 * 1. Zero Frame Allocation: Caches [Stroke] and rewinds [Path] in-place to avoid GC thrashing at 120 FPS.
 * 2. Continuous Phase Propagation: Traveling wave propagates along X axis without phase discontinuities.
 * 3. Proportional Overstretch: Amplitude scales beyond nominal 1.0f lockstep with pull stretch up to 1.5f.
 *
 * @param amplitude Dynamic wave amplitude multiplier (0.0f = flat hairline, 1.0f = nominal wave, 1.5f = overstretched wave).
 * @param modifier Composable modifier applied to the canvas layout bounds.
 * @param color Color used to stroke the active wavy indicator path.
 * @param trackColor Optional background track line color drawn behind flat hairline. Defaults to [Color.Transparent].
 * @param strokeWidth Thickness of the wavy line stroke.
 * @param wavelength Peak-to-peak horizontal distance of one complete wave cycle.
 * @param waveSpeed Horizontal velocity in DP per second for the travelling wave animation.
 * @param baseAvailableHeight Nominal available vertical oscillation space at 1.0x amplitude.
 */
@Composable
fun ExpressiveWavyProgressBar(
    amplitude: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = Color.Transparent,
    strokeWidth: Dp = 4.dp,
    wavelength: Dp = 28.dp,
    waveSpeed: Dp = 16.dp,
    baseAvailableHeight: Dp = 12.dp
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val wavelengthPx = with(density) { wavelength.toPx() }
    val baseAvailableHeightPx = with(density) { baseAvailableHeight.toPx() }
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

    val cycleDurationMs = remember(wavelength, waveSpeed) {
        if (waveSpeed > 0.dp && wavelength > 0.dp) {
            ((wavelength / waveSpeed) * 1000).toInt().coerceAtLeast(100)
        } else {
            1750
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "ExpressiveWaveTransition")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = cycleDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ExpressiveWavePhase"
    )

    val wavePath = remember { Path() }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    }

    Canvas(
        modifier = modifier.clipToBounds()
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        if (width <= 0f || height <= 0f) return@Canvas

        val maxAvailableHeightPx = (height - strokeWidthPx).coerceAtLeast(0f)

        // When amplitude is flat or bounds too short for crests, render flat hairline
        if (amplitude <= 0.001f || height <= strokeWidthPx || wavelengthPx <= 0f) {
            if (trackColor != Color.Transparent && trackColor.alpha > 0.01f) {
                drawLine(
                    color = trackColor,
                    start = Offset(0f, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            }
            drawLine(
                color = color,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round
            )
            return@Canvas
        }

        // Peak control point displacement scaled dynamically with amplitude
        val peakControlOffset = (baseAvailableHeightPx * amplitude).coerceAtMost(maxAvailableHeightPx)

        val halfWavelength = wavelengthPx / 2f
        val rawShift = wavePhase * wavelengthPx
        val waveShift = if (isLtr) rawShift else -rawShift
        val normalizedShift = ((waveShift % wavelengthPx) + wavelengthPx) % wavelengthPx

        wavePath.rewind()

        val startX = -2f * wavelengthPx + normalizedShift
        wavePath.moveTo(startX, centerY)

        var currentX = startX
        var isCrest = true

        while (currentX < width + wavelengthPx) {
            val controlX = currentX + halfWavelength / 2f
            val controlY = if (isCrest) centerY - peakControlOffset else centerY + peakControlOffset
            val targetX = currentX + halfWavelength
            wavePath.quadraticTo(controlX, controlY, targetX, centerY)
            currentX = targetX
            isCrest = !isCrest
        }

        drawPath(
            path = wavePath,
            color = color,
            style = strokeStyle
        )
    }
}

@Preview(name = "Expressive Wavy Progress Bar Preview", showBackground = true)
@Composable
private fun ExpressiveWavyProgressBarPreview() {
    GalleryPreviewTheme {
        ExpressiveWavyProgressBar(
            amplitude = 1.0f,
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
        )
    }
}
