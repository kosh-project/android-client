package project.kosh.feature.auth

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Official Kosh Brand Logo composable.
 *
 * Renders the brand isometric geometry with an optional subtle ambient glow.
 */
@Composable
fun KoshBrandLogo(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    showGlow: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "kosh_logo_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (showGlow) {
            Box(
                modifier = Modifier
                    .size(size * 0.9f)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFC7C9FF).copy(alpha = glowAlpha),
                                Color(0xFF151922).copy(alpha = 0f)
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }

        Image(
            painter = painterResource(id = R.drawable.ic_kosh_brand_logo),
            contentDescription = "Kosh Logo",
            modifier = Modifier.size(size)
        )
    }
}
