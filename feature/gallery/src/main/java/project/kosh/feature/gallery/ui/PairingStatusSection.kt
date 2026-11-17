package project.kosh.feature.gallery.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual section rendered when a bidirectional device pairing exchange finishes successfully.
 *
 * @param message Confirmation text indicating which device connected.
 * @param onDone Invoked when the user confirms completion and dismisses the bottom sheet.
 * @param modifier Composable modifier applied to the section layout.
 */
@Composable
fun PairingSuccessSection(
    message: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your master vault key was securely transferred to the new device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Done", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

/**
 * Visual section rendered when an ECDH key exchange or relay mailbox handshake fails.
 *
 * @param errorMessage Detailed error description from the pairing state machine.
 * @param onRetry Invoked when the user taps retry to generate a fresh mailbox.
 * @param onClose Invoked when the user elects to dismiss the bottom sheet.
 * @param modifier Composable modifier applied to the section layout.
 */
@Composable
fun PairingErrorSection(
    errorMessage: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(14.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Retry pairing")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onClose,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Previews

@Preview(name = "Pairing Success Light", showBackground = true)
@Composable
private fun PairingSuccessLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        PairingSuccessSection(message = "Pixel 9 Pro successfully linked", onDone = {})
    }
}

@Preview(name = "Pairing Error Dark", showBackground = true)
@Composable
private fun PairingErrorDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        PairingErrorSection(
            errorMessage = "Relay mailbox timed out waiting for peer join.",
            onRetry = {},
            onClose = {}
        )
    }
}
