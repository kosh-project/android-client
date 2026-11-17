package project.kosh.feature.gallery.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview

/**
 * Security confirmation dialog presented when a user initiates account sign out.
 *
 * Explains the cryptographic consequences of logging out: clearing session tokens
 * from Android KeyStore and locking the local Master Vault Key in memory.
 *
 * @param onConfirm Invoked when the user confirms the sign-out operation.
 * @param onDismiss Invoked when the user dismisses or cancels the dialog.
 */
@Composable
fun LogoutConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Log Out", fontWeight = FontWeight.Bold)
        },
        text = {
            Text(
                "Logging out will lock your local Master Vault Key and clear the active session token from Android KeyStore. You will need to sign in again to access your encrypted files."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Log Out")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// Previews

@Preview(name = "Logout Dialog Light", showBackground = true)
@Composable
private fun LogoutConfirmationDialogLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        LogoutConfirmationDialog(onConfirm = {}, onDismiss = {})
    }
}

@Preview(name = "Logout Dialog Dark", showBackground = true)
@Composable
private fun LogoutConfirmationDialogDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        LogoutConfirmationDialog(onConfirm = {}, onDismiss = {})
    }
}
