package project.kosh.feature.gallery.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import project.kosh.feature.auth.QrCodeGenerator

/**
 * Click-to-copy card rendering a formatted 8-character device pairing code (e.g. "ABCD - EFGH").
 *
 * @param mailboxId Ephemeral mailbox identifier.
 * @param isEnabled Whether click-to-copy is active.
 * @param onCopy Invoked when the user taps to copy the code to clipboard.
 * @param modifier Composable modifier applied to the surface.
 */
@Composable
fun PairingCodeCard(
    mailboxId: String,
    isEnabled: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedCode = if (mailboxId.length == 8) {
        "${mailboxId.take(4).uppercase()} - ${mailboxId.drop(4).uppercase()}"
    } else {
        mailboxId.uppercase()
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = isEnabled, onClick = onCopy)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Pairing code",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formattedCode,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Renders an unslop-compliant QR code tile generated from raw JSON payload bytes.
 *
 * @param qrPayloadJson Serialized pairing JSON payload.
 * @param modifier Composable modifier applied to the surface.
 */
@Composable
fun PairingQrCodeCard(
    qrPayloadJson: String,
    modifier: Modifier = Modifier
) {
    val qrBitmap = remember(qrPayloadJson) {
        try {
            QrCodeGenerator.generate(qrPayloadJson, 512)
        } catch (_: Exception) {
            null
        }
    }

    if (qrBitmap != null) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            shadowElevation = 2.dp,
            modifier = modifier.size(210.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(12.dp)
            ) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = "Pairing QR Code",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Overlay surface displayed when a pairing mailbox session times out.
 *
 * @param overlayAlpha Interpolated opacity of the alert card.
 * @param onGenerateNewCode Invoked when the user taps to reinitialize a pairing mailbox.
 * @param modifier Composable modifier applied to the surface.
 */
@Composable
fun PairingExpiredOverlay(
    overlayAlpha: Float,
    onGenerateNewCode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f * overlayAlpha),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .graphicsLayer { alpha = overlayAlpha }
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Expired",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Pairing session expired",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The QR code has expired for security.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onGenerateNewCode,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Generate new code")
            }
        }
    }
}

// Previews

@Preview(name = "Pairing Code Light", showBackground = true)
@Composable
private fun PairingCodeCardLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        PairingCodeCard(mailboxId = "a1b2c3d4", isEnabled = true, onCopy = {})
    }
}
