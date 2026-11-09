package project.kosh.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Playfully animated registration progress bar.
 *
 * Displays current registration onboarding progress (Steps 1–4) with a wavy sparkle shimmer,
 * smooth bouncy progress animations, and step title badges to keep users motivated.
 *
 * Steps:
 * 1. Account
 * 2. Recovery Phrase
 * 3. Device Setup
 * 4. Biometric Passkey
 *
 * @param currentStep 1-indexed step number (1..4).
 * @param totalSteps Total onboarding steps (defaults to 4).
 * @param modifier Optional layout modifier.
 */
@Composable
fun RegistrationProgressBar(
    currentStep: Int,
    totalSteps: Int = 4,
    modifier: Modifier = Modifier
) {
    val stepTitles = listOf(
        "Account",
        "Recovery Phrase",
        "Device Setup",
        "Biometric Passkey"
    )
    val clampedStep = currentStep.coerceIn(1, totalSteps)
    val currentTitle = stepTitles.getOrElse(clampedStep - 1) { "Step $clampedStep" }

    // Smooth bouncy spring progress transition
    val animatedProgressFraction by animateFloatAsState(
        targetValue = clampedStep / totalSteps.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "reg_progress_fraction"
    )

    // Infinite wavy sparkle / shimmer transition
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer_transition")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    // Playful sparkle icon pulse and rotation
    val sparkleScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sparkle_scale"
    )
    val sparkleRotate by infiniteTransition.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sparkle_rotate"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        // Top Row: Step Title & Sparkle Indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedContent(
                targetState = currentTitle,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                label = "step_title_animation"
            ) { title ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Step $clampedStep of $totalSteps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Playful Sparkle Emoji with animation
            Text(
                text = "✨",
                fontSize = 14.sp,
                modifier = Modifier
                    .scale(sparkleScale)
                    .rotate(sparkleRotate)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Progress Track with wavy shimmering bar
        val trackColor = MaterialTheme.colorScheme.surfaceVariant
        val shimmerBrush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFC7C9CB),
                Color(0xFFEEEFEF),
                Color(0xFFC7C9FF),
                Color(0xFFEEEFEF),
                Color(0xFFC7C9CB)
            ),
            start = Offset(shimmerOffset, 0f),
            end = Offset(shimmerOffset + 240f, 0f),
            tileMode = TileMode.Clamp
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgressFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush)
            )
        }

        Spacer(Modifier.height(10.dp))

        // Segmented Step Dots
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (stepIndex in 1..totalSteps) {
                val isCompleted = stepIndex < clampedStep
                val isCurrent = stepIndex == clampedStep

                val dotColor = when {
                    isCompleted -> MaterialTheme.colorScheme.primary
                    isCurrent -> Color(0xFFC7C9FF)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }

                Surface(
                    shape = CircleShape,
                    color = dotColor,
                    modifier = Modifier.size(if (isCurrent) 10.dp else 8.dp)
                ) {
                    if (isCompleted) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
