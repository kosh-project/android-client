package project.kosh.feature.gallery.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import project.kosh.feature.gallery.ui.icons.FolderIcon
import project.kosh.feature.gallery.ui.icons.LinkIcon
import project.kosh.feature.gallery.ui.icons.LogoutIcon
import project.kosh.feature.gallery.ui.icons.NetworkPingIcon
import project.kosh.feature.gallery.ui.icons.SettingsIcon
import project.kosh.feature.gallery.ui.icons.SyncIcon

/**
 * Dropdown menu anchored to the floating three-dot action island exposing settings,
 * device pairing, folder synchronization, server ping, and account logout actions.
 *
 * @param expanded Whether the popup dropdown is currently displayed.
 * @param onDismissRequest Invoked when dismissing the dropdown popup.
 * @param isPinging Whether a server latency ping request is in flight.
 * @param onOpenFolderSelection Invoked to open the media album selection bottom sheet.
 * @param onStartPairing Invoked to begin device-to-device pairing.
 * @param onTriggerSync Invoked to request a manual delta-CRDT sync pass.
 * @param onOpenServerConfig Invoked to navigate to server configuration settings.
 * @param onPingServer Invoked to test remote server reachability and latency.
 * @param onLogoutRequest Invoked when the user selects log out from the menu.
 */
@Composable
fun GalleryTopIslandDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    isPinging: Boolean,
    onOpenFolderSelection: () -> Unit,
    onStartPairing: () -> Unit,
    onTriggerSync: () -> Unit,
    onOpenServerConfig: () -> Unit,
    onPingServer: () -> Unit,
    onLogoutRequest: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            text = { Text("Media folders") },
            leadingIcon = {
                Icon(
                    imageVector = FolderIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onDismissRequest()
                onOpenFolderSelection()
            }
        )

        DropdownMenuItem(
            text = { Text("Link device") },
            leadingIcon = {
                Icon(
                    imageVector = LinkIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onDismissRequest()
                onStartPairing()
            }
        )

        DropdownMenuItem(
            text = { Text("Sync vault") },
            leadingIcon = {
                Icon(
                    imageVector = SyncIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onDismissRequest()
                onTriggerSync()
            }
        )

        DropdownMenuItem(
            text = { Text("Server settings") },
            leadingIcon = {
                Icon(
                    imageVector = SettingsIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onDismissRequest()
                onOpenServerConfig()
            }
        )

        DropdownMenuItem(
            text = {
                if (isPinging) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pinging server...")
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    }
                } else {
                    Text("Ping server")
                }
            },
            leadingIcon = {
                Icon(
                    imageVector = NetworkPingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            enabled = !isPinging,
            onClick = {
                onDismissRequest()
                onPingServer()
            }
        )

        HorizontalDivider()

        DropdownMenuItem(
            text = {
                Text(
                    text = "Log out",
                    color = MaterialTheme.colorScheme.error
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = LogoutIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = {
                onDismissRequest()
                onLogoutRequest()
            }
        )
    }
}

// Previews

@Preview(name = "Dropdown Menu Light", showBackground = true)
@Composable
private fun GalleryTopIslandDropdownMenuLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        GalleryTopIslandDropdownMenu(
            expanded = true,
            onDismissRequest = {},
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

@Preview(name = "Dropdown Menu Pinging Dark", showBackground = true)
@Composable
private fun GalleryTopIslandDropdownMenuPingingDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        GalleryTopIslandDropdownMenu(
            expanded = true,
            onDismissRequest = {},
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
