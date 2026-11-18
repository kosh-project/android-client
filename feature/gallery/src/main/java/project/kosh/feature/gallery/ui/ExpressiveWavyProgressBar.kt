package project.kosh.feature.gallery.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlin.math.sin

/**
 * Continuous wavy progress indicator drawn directly on [Canvas] with anchored rounded ends.
 *
 * Eliminates viewport clipping artifacts by anchoring both terminal ends to the center baseline
 * with [StrokeCap.Round], while applying a smooth Hermite envelope taper near the boundaries.
 * Delivers continuous traveling wave undulation without hard-sliced flat vertical boundaries.
 *
 * ### Architectural Guarantees
 * 1. Zero Frame Allocation: Caches [Stroke] and rewinds [Path] in-place to avoid GC at 120 FPS.
 * 2. Anchored Rounded Ends: Both endpoints terminate on the centerline with full circular caps.
 * 3. Hermite Envelope Taper: Smoothly ramps wave amplitude near the ends to eliminate edge popping.
 * 4. Proportional Overstretch: Amplitude scales beyond 1.0f lockstep with gesture stretch up to 1.5f.
 *
 * @param amplitude Dynamic wave amplitude multiplier (0.0f = flat hairline, 1.0f = nominal, 1.5f = overstretch).
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

        val capRadius = strokeWidthPx / 2f
        val startX = capRadius + 1f
        val endX = (width - capRadius - 1f).coerceAtLeast(startX)
        val usableWidth = endX - startX

        if (usableWidth <= 0f) return@Canvas

        val maxAvailableHeightPx = (height - strokeWidthPx).coerceAtLeast(0f)

        // When amplitude is flat or bounds too short for crests, render anchored flat hairline
        if (amplitude <= 0.001f || height <= strokeWidthPx || wavelengthPx <= 0f) {
            if (trackColor != Color.Transparent && trackColor.alpha > 0.01f) {
                drawLine(
                    color = trackColor,
                    start = Offset(startX, centerY),
                    end = Offset(endX, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            }
            drawLine(
                color = color,
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round
            )
            return@Canvas
        }

        val maxDisplacementPx = maxAvailableHeightPx / 2f
        val targetDisplacementPx = (baseAvailableHeightPx / 2f) * amplitude
        val peakDisplacementPx = targetDisplacementPx.coerceAtMost(maxDisplacementPx)

        val rawShift = wavePhase * wavelengthPx
        val waveShift = if (isLtr) rawShift else -rawShift
        val k = (2.0 * Math.PI / wavelengthPx).toFloat()
        val phaseRad = (2.0 * Math.PI * (waveShift / wavelengthPx)).toFloat()

        // Taper zone over half wavelength to anchor ends smoothly at (startX, centerY) and (endX, centerY)
        val taperDistance = (wavelengthPx / 2f).coerceAtMost(usableWidth / 2f)
        val stepPx = with(density) { 2.dp.toPx() }.coerceAtLeast(1f)

        wavePath.rewind()
        wavePath.moveTo(startX, centerY)

        var currentX = startX
        while (currentX < endX) {
            val distanceFromStart = currentX - startX
            val distanceFromEnd = endX - currentX
            val envelope = when {
                taperDistance <= 0f -> 1f
                distanceFromStart < taperDistance -> {
                    val s = distanceFromStart / taperDistance
                    (s * s * (3f - 2f * s)).coerceIn(0f, 1f)
                }
                distanceFromEnd < taperDistance -> {
                    val s = distanceFromEnd / taperDistance
                    (s * s * (3f - 2f * s)).coerceIn(0f, 1f)
                }
                else -> 1f
            }

            val y = centerY - (peakDisplacementPx * envelope * sin(k * (currentX - startX) - phaseRad))
            wavePath.lineTo(currentX, y)
            currentX += stepPx
        }

        wavePath.lineTo(endX, centerY)

        if (trackColor != Color.Transparent && trackColor.alpha > 0.01f) {
            drawLine(
                color = trackColor,
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round
            )
        }

        drawPath(
            path = wavePath,
            color = color,
            style = strokeStyle
        )
    }
}

@Preview(name = "Expressive Wavy Progress Bar States Preview", showBackground = true)
@Composable
private fun ExpressiveWavyProgressBarPreview() {
    GalleryPreviewTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Flat Hairline (Amplitude 0.0)", style = MaterialTheme.typography.labelSmall)
            ExpressiveWavyProgressBar(
                amplitude = 0.0f,
                modifier = Modifier.fillMaxWidth().height(16.dp)
            )
            Text(text = "Nominal Wave (Amplitude 1.0)", style = MaterialTheme.typography.labelSmall)
            ExpressiveWavyProgressBar(
                amplitude = 1.0f,
                modifier = Modifier.fillMaxWidth().height(16.dp)
            )
            Text(text = "Overstretched Wave (Amplitude 1.5)", style = MaterialTheme.typography.labelSmall)
            ExpressiveWavyProgressBar(
                amplitude = 1.5f,
                modifier = Modifier.fillMaxWidth().height(22.dp)
            )
        }
    }
}
