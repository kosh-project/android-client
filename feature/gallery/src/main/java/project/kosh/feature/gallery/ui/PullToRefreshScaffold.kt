package project.kosh.feature.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Spring-based pull-to-refresh layout wrapper providing overstretch dynamics,
 * elastic scale relaxation, and an indeterminate Material 3 Expressive wavy progress indicator.
 *
 * ### Architectural Guarantees
 * 1. **Kinetic Wave Blooming:** Wave amplitude scales dynamically with pull stretch from 0.0f
 *    (flat hairline) up to 1.0f (full expressive wave) at threshold, tracking 1:1 with finger motion.
 * 2. **Elastic Box Height Coupling:** The indicator canvas bounds expand from 4dp to 16dp
 *    proportional to wave amplitude during pull-down, collapsing back to 0dp upon dismissal.
 * 3. **Persistent Host Resolution:** Remains active and visible for as long as background
 *    sync / network host discovery executes, avoiding premature dismissal when the server is offline.
 * 4. **Dismissal Flattening & Shrink:** Animates amplitude back down to 0.0f and height to 0dp
 *    during dismissal collapse.
 * 5. **Island Bar Clearance:** Automatically offsets downward when [isTopBarVisible] is true,
 *    preventing visual collision with the floating passkey identity pill and three-dot action button.
 *
 * @param isRefreshing Whether a background data sync is actively executing.
 * @param onRefresh Invoked when the user pulls past the threshold to request a fresh sync.
 * @param modifier Composable modifier applied to the root container.
 * @param isTopBarVisible Whether the floating top island bar is currently visible.
 * @param content The scrollable content hierarchy (such as a LazyVerticalGrid) to enclose.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PullToRefreshScaffold(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    isTopBarVisible: Boolean = true,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isManualRefreshingVisual by remember { mutableStateOf(false) }
    var isDismissingAfterRefresh by remember { mutableStateOf(false) }

    val pullToRefreshAnim = remember { Animatable(0f, Float.VectorConverter) }
    val pullToRefreshState = remember(pullToRefreshAnim) {
        object : PullToRefreshState {
            override val distanceFraction: Float
                get() = pullToRefreshAnim.value

            override val isAnimating: Boolean
                get() = pullToRefreshAnim.isRunning

            override suspend fun animateToThreshold() {
                pullToRefreshAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }

            override suspend fun animateToHidden() {
                pullToRefreshAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }

            override suspend fun snapTo(targetValue: Float) {
                pullToRefreshAnim.snapTo(targetValue)
            }
        }
    }

    val activelyRefreshing = isManualRefreshingVisual || isRefreshing

    val overstretchProgress = pullToRefreshState.distanceFraction.coerceIn(0f, 1.5f)
    val overstretchY = (96.dp * overstretchProgress)

    val animatedScaleRelaxation by animateFloatAsState(
        targetValue = if (activelyRefreshing || isDismissingAfterRefresh) 0f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pullScaleRelaxation"
    )
    val overstretchScaleY = 1f + (overstretchProgress * 0.06f * animatedScaleRelaxation)

    val topBarClearance by animateDpAsState(
        targetValue = if (isTopBarVisible) 52.dp else 4.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "wavyBarTopClearance"
    )

    // Dynamic wave amplitude and height coupling
    val pullFraction = (pullToRefreshState.distanceFraction / 1.0f).coerceIn(0f, 1f)
    val isGesturePulling = pullFraction > 0.01f && !activelyRefreshing && !isDismissingAfterRefresh

    val rawTargetAmplitude = when {
        isDismissingAfterRefresh -> 0f
        activelyRefreshing -> 1f
        isGesturePulling -> pullFraction
        else -> 0f
    }

    val animatedAmplitude by animateFloatAsState(
        targetValue = rawTargetAmplitude,
        animationSpec = if (isGesturePulling) {
            snap()
        } else {
            tween(durationMillis = 250, easing = FastOutSlowInEasing)
        },
        label = "waveAmplitude"
    )

    val rawTargetHeight = when {
        isDismissingAfterRefresh -> 0.dp
        activelyRefreshing -> 16.dp
        isGesturePulling -> 4.dp + (12.dp * pullFraction)
        else -> 0.dp
    }

    val animatedBarHeight by animateDpAsState(
        targetValue = rawTargetHeight,
        animationSpec = if (isGesturePulling) {
            snap()
        } else {
            tween(durationMillis = 250, easing = FastOutSlowInEasing)
        },
        label = "wavyBarHeight"
    )

    val animatedAlpha by animateFloatAsState(
        targetValue = when {
            isDismissingAfterRefresh -> 0f
            activelyRefreshing -> 1f
            isGesturePulling -> (pullFraction * 2.5f).coerceIn(0f, 1f)
            else -> 0f
        },
        animationSpec = tween(durationMillis = 150),
        label = "wavyBarAlpha"
    )

    val executeRefresh: () -> Unit = {
        coroutineScope.launch {
            try {
                isDismissingAfterRefresh = false
                isManualRefreshingVisual = true
                val startTime = System.currentTimeMillis()
                onRefresh()

                // Stay active as long as background sync / host resolution is executing
                while (isRefreshing) {
                    delay(50L)
                }

                // Minimum aesthetic duration so fast syncs don't flicker
                val elapsed = System.currentTimeMillis() - startTime
                val minDisplayMs = 600L
                if (elapsed < minDisplayMs) {
                    delay(minDisplayMs - elapsed)
                }

                isDismissingAfterRefresh = true
                isManualRefreshingVisual = false
                pullToRefreshState.animateToHidden()
                delay(250L) // Allow amplitude -> 0 and height -> 0 shrink animations to finish
            } finally {
                isDismissingAfterRefresh = false
                isManualRefreshingVisual = false
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = activelyRefreshing,
        onRefresh = executeRefresh,
        state = pullToRefreshState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        indicator = {}
    ) {
        // Overstretched Content Surface with Y displacement and elastic scale
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = overstretchY.toPx()
                    scaleY = overstretchScaleY
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
        ) {
            content()
        }

        // Expressive Full-Width Continuous Wavy Progress Bar docked at top
        val isIndicatorVisible = (isGesturePulling || activelyRefreshing || isDismissingAfterRefresh) &&
            animatedBarHeight > 0.5.dp && animatedAlpha > 0.01f

        if (isIndicatorVisible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = topBarClearance.coerceAtLeast(0.dp), start = 8.dp, end = 8.dp)
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = animatedAlpha
                    }
            ) {
                LinearWavyProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(animatedBarHeight),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    amplitude = { animatedAmplitude },
                    wavelength = 28.dp,
                    waveSpeed = 16.dp,
                    stopSize = 0.dp,
                    gapSize = 0.dp
                )
            }
        }
    }
}

// Previews

@Preview(name = "PullToRefresh Idle Preview", showBackground = true)
@Composable
private fun PullToRefreshScaffoldIdlePreview() {
    GalleryPreviewTheme {
        PullToRefreshScaffold(
            isRefreshing = false,
            onRefresh = {}
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Pull down to refresh gallery",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
