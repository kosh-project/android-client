package project.kosh.feature.gallery.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import project.kosh.core.auth.PairingState
import project.kosh.core.model.ActivePairingSession
import project.kosh.feature.gallery.ui.icons.LinkIcon

/**
 * Interactive Modal Bottom Sheet orchestrating bidirectional device-to-device pairing.
 *
 * Facilitates transferring the 60-byte Master Vault Key between devices via the blind
 * relay mailbox protocol (Flow A / Flow B). Renders dynamic QR codes, copyable pairing codes,
 * session expiration blur overlays, camera scanner triggers, and manual code entry.
 *
 * @param activePairingSession Current active pairing session metadata, or null if uninitialized.
 * @param pairingState State of the ECDH handshake state machine.
 * @param pairingSuccessMessage Optional confirmation text when pairing completes.
 * @param isSessionExpired Whether the ephemeral mailbox session reached its TTL.
 * @param blurRadius Visual blur applied to QR code when expired.
 * @param expiredOverlayAlpha Visual opacity of the expiration overlay card.
 * @param onRetryPairing Invoked to generate a fresh pairing session.
 * @param onScanCameraQr Invoked to launch the camera QR code scanner.
 * @param onFulfillReceiverCode Invoked with an 8-character code entered manually.
 * @param onDismiss Invoked when closing the pairing bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkDeviceBottomSheet(
    activePairingSession: ActivePairingSession?,
    pairingState: PairingState?,
    pairingSuccessMessage: String?,
    isSessionExpired: Boolean,
    blurRadius: Dp,
    expiredOverlayAlpha: Float,
    onRetryPairing: () -> Unit,
    onScanCameraQr: () -> Unit,
    onFulfillReceiverCode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showManualCodeEntry by remember { mutableStateOf(false) }
    var manualPairingCode by remember { mutableStateOf("") }

    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = LinkIcon,
                        contentDescription = "Link Device",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Link Another Device",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Scan this QR code with your new device, or scan a code from that device to securely transfer your Master Vault Key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            if (pairingSuccessMessage != null) {
                PairingSuccessSection(
                    message = pairingSuccessMessage,
                    onDone = onDismiss
                )
            } else {
                val currentMailboxId = activePairingSession?.mailboxId
                    ?: (pairingState as? PairingState.AwaitingJoin)?.mailboxId
                    ?: (pairingState as? PairingState.Ready)?.mailboxId
                val currentQrJson = activePairingSession?.qrPayloadJson
                    ?: (pairingState as? PairingState.AwaitingJoin)?.qrPayloadJson
                    ?: (pairingState as? PairingState.Ready)?.qrPayloadJson

                val isError = pairingState is PairingState.Error && !isSessionExpired

                if (isError) {
                    PairingErrorSection(
                        errorMessage = pairingState.message,
                        onRetry = onRetryPairing,
                        onClose = onDismiss
                    )
                } else if (currentMailboxId != null && currentQrJson != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .blur(blurRadius),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            PairingCodeCard(
                                mailboxId = currentMailboxId,
                                isEnabled = !isSessionExpired,
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString(currentMailboxId))
                                    Toast.makeText(context, "Pairing code copied", Toast.LENGTH_SHORT).show()
                                }
                            )

                            Spacer(Modifier.height(16.dp))

                            PairingQrCodeCard(qrPayloadJson = currentQrJson)

                            Spacer(Modifier.height(14.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Scan this code with your new device...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (expiredOverlayAlpha > 0f) {
                            PairingExpiredOverlay(
                                overlayAlpha = expiredOverlayAlpha,
                                onGenerateNewCode = onRetryPairing
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(modifier = Modifier.fillMaxWidth(0.9f))
                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Or scan a QR code from another device",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onScanCameraQr,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text("Scan camera QR", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { showManualCodeEntry = !showManualCodeEntry },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text(if (showManualCodeEntry) "Hide code" else "Enter code", fontSize = 13.sp)
                        }
                    }

                    if (showManualCodeEntry) {
                        Spacer(Modifier.height(14.dp))
                        ManualPairingCodeInput(
                            code = manualPairingCode,
                            onCodeChange = { manualPairingCode = it },
                            onFulfill = { onFulfillReceiverCode(it) }
                        )
                    }
                } else {
                    CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Initializing secure pairing mailbox...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// Previews

@Preview(name = "Link Device Light", showBackground = true)
@Composable
private fun LinkDeviceBottomSheetLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        PairingCodeCard(
            mailboxId = "KOSH7799",
            isEnabled = true,
            onCopy = {}
        )
    }
}

@Preview(name = "Link Device Dark", showBackground = true)
@Composable
private fun LinkDeviceBottomSheetDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        PairingCodeCard(
            mailboxId = "VAULT123",
            isEnabled = false,
            onCopy = {}
        )
    }
}
