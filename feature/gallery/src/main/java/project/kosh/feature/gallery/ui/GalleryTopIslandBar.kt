package project.kosh.feature.gallery.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import project.kosh.feature.gallery.ui.icons.MoreVertIcon

/**
 * Floating three-dot action island docked at the top-right corner of the gallery.
 *
 * Automatically hides during downward scroll gestures to maximize screen real estate,
 * reappearing when scrolling upward or paused at the top of the feed.
 *
 * @param isVisible Whether the island should currently be visible.
 * @param activeUser Display name or handle of the authenticated user.
 * @param isSessionActive Whether cryptographic session tokens in KeyStore are valid.
 * @param isPinging Whether a server latency ping request is in flight.
 * @param onOpenFolderSelection Invoked to open the media album selection bottom sheet.
 * @param onStartPairing Invoked to begin device-to-device pairing.
 * @param onTriggerSync Invoked to request a manual delta-CRDT sync pass.
 * @param onOpenServerConfig Invoked to navigate to server configuration settings.
 * @param onPingServer Invoked to test remote server reachability and latency.
 * @param onLogoutRequest Invoked when the user selects log out from the menu.
 * @param modifier Composable modifier applied to the outer layout container.
 */
@Composable
fun GalleryTopIslandBar(
    isVisible: Boolean,
    activeUser: String?,
    isSessionActive: Boolean,
    isPinging: Boolean,
    onOpenFolderSelection: () -> Unit,
    onStartPairing: () -> Unit,
    onTriggerSync: () -> Unit,
    onOpenServerConfig: () -> Unit,
    onPingServer: () -> Unit,
    onLogoutRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showTopMenu by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = isVisible || showTopMenu,
        enter = fadeIn(animationSpec = tween(durationMillis = 200)) + slideInVertically(
            initialOffsetY = { -it / 2 },
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
        ),
        exit = fadeOut(animationSpec = tween(durationMillis = 150)) + slideOutVertically(
            targetOffsetY = { -it / 2 },
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
        ),
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 8.dp, end = 12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            shadowElevation = 4.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isSessionActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(7.dp)
                ) {}

                if (!activeUser.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = activeUser,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Spacer(Modifier.width(4.dp))

                Box {
                    IconButton(
                        onClick = { showTopMenu = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = MoreVertIcon,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    GalleryTopIslandDropdownMenu(
                        expanded = showTopMenu,
                        onDismissRequest = { showTopMenu = false },
                        isPinging = isPinging,
                        onOpenFolderSelection = onOpenFolderSelection,
                        onStartPairing = onStartPairing,
                        onTriggerSync = onTriggerSync,
                        onOpenServerConfig = onOpenServerConfig,
                        onPingServer = onPingServer,
                        onLogoutRequest = onLogoutRequest
                    )
                }
            }
        }
    }
}

// Previews

@Preview(name = "Top Island Light", showBackground = true)
@Composable
private fun GalleryTopIslandBarLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        GalleryTopIslandBar(
            isVisible = true,
            activeUser = "satoshi",
            isSessionActive = true,
            isPinging = false,
            onOpenFolderSelection = {},
            onStartPairing = {},
            onTriggerSync = {},
            onOpenServerConfig = {},
            onPingServer = {},
            onLogoutRequest = {}
        )
    }
}

@Preview(name = "Top Island Dark", showBackground = true)
@Composable
private fun GalleryTopIslandBarDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        GalleryTopIslandBar(
            isVisible = true,
            activeUser = "alice_vault",
            isSessionActive = false,
            isPinging = true,
            onOpenFolderSelection = {},
            onStartPairing = {},
            onTriggerSync = {},
            onOpenServerConfig = {},
            onPingServer = {},
            onLogoutRequest = {}
        )
    }
}
