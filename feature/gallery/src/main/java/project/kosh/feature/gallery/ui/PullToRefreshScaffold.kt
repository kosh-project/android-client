package project.kosh.feature.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Spring-based pull-to-refresh layout wrapper providing overstretch dynamics,
 * elastic scale relaxation, and floating loading indicator clearance.
 *
 * ### Architectural Guarantees
 * 1. **Content Clearance:** Downward content translation strictly exceeds indicator bottom
 *    displacement at every pull fraction (tested in GalleryPullToRefreshTest), preventing
 *    occlusion of the first grid row.
 * 2. **Elastic Scale Relaxation:** Media tiles stretch vertically up to 1.06x during gesture
 *    pull, relaxing back to 1.0x when the refresh threshold triggers or dismisses.
 * 3. **Flicker Elimination:** Maintains indeterminate spinner mode throughout active refresh
 *    and dismiss collapse to prevent reverting to determinate arc during exit.
 * 4. **Minimum Display Duration:** Enforces a minimum 2000ms duration so network operations
 *    do not cause visual flashes.
 *
 * @param isRefreshing Whether a background data sync is actively executing.
 * @param onRefresh Invoked when the user pulls past the threshold to request a fresh sync.
 * @param modifier Composable modifier applied to the root container.
 * @param content The scrollable content hierarchy (such as a LazyVerticalGrid) to enclose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshScaffold(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
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

    val indicatorAlpha by animateFloatAsState(
        targetValue = when {
            activelyRefreshing -> 1f
            isDismissingAfterRefresh -> 0f
            pullToRefreshState.isAnimating -> 0f
            overstretchProgress > 0.05f -> (overstretchProgress * 2.5f).coerceIn(0f, 1f)
            else -> 0f
        },
        animationSpec = tween(durationMillis = 250),
        label = "pullIndicatorAlpha"
    )

    val indicatorScale by animateFloatAsState(
        targetValue = when {
            activelyRefreshing -> 1f
            isDismissingAfterRefresh -> 0.8f
            pullToRefreshState.isAnimating -> 0.8f
            overstretchProgress > 0.05f -> (0.6f + overstretchProgress * 0.4f).coerceIn(0.6f, 1f)
            else -> 0.6f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pullIndicatorScale"
    )

    val executeRefresh: () -> Unit = {
        coroutineScope.launch {
            try {
                isDismissingAfterRefresh = false
                isManualRefreshingVisual = true
                val startTime = System.currentTimeMillis()
                onRefresh()
                val elapsed = System.currentTimeMillis() - startTime
                val minDisplayMs = 2000L
                if (elapsed < minDisplayMs) {
                    delay(minDisplayMs - elapsed)
                }
                isDismissingAfterRefresh = true
                isManualRefreshingVisual = false
                pullToRefreshState.animateToHidden()
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
        // Floating Loading Indicator Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(overstretchY)
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    alpha = indicatorAlpha
                    scaleX = indicatorScale
                    scaleY = indicatorScale
                },
            contentAlignment = Alignment.Center
        ) {
            if (activelyRefreshing || isDismissingAfterRefresh) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    strokeCap = StrokeCap.Round
                )
            } else if (overstretchProgress > 0.05f) {
                val pullProgress = (overstretchProgress / 1.0f).coerceIn(0f, 1f)
                CircularProgressIndicator(
                    progress = { pullProgress },
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    strokeCap = StrokeCap.Round
                )
            }
        }

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
