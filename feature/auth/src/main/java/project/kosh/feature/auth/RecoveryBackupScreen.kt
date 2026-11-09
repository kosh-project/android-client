package project.kosh.feature.auth

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated 24-Word Recovery Phrase Screen presented immediately after account registration.
 *
 * Implements a strict cryptographic barrier: users cannot proceed to their vault until acknowledging
 * they have preserved their 24-word recovery phrase in offline cold storage.
 *
 * Features:
 * - Playful registration progress bar at top (Step 2 of 4).
 * - Meaningful subtitle replacing useless warnings.
 * - Monospaced 3-column numbered 24-word grid.
 * - Plaintext document export (.txt) with TextSnippet icon.
 * - Printable paper wallet document export (.pdf) with PictureAsPdf icon.
 * - Secure clipboard copy option with CopyAll icon and simplified privacy alert.
 * - Acknowledgment barrier checkbox unlocking the primary navigation action.
 *
 * @param words The 24 BIP-39 mnemonic recovery words.
 * @param serverHost The server domain or IP endpoint for inclusion in paper wallet metadata.
 * @param onProceed Navigation callback invoked when the user confirms cold storage backup.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryBackupScreen(
    words: List<String>,
    serverHost: String,
    onProceed: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var hasAcknowledged by remember { mutableStateOf(false) }
    var showClipboardAlert by remember { mutableStateOf(false) }

    val txtExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Unable to open destination file")
                outputStream.use { writePlainTextRecoveryFile(words, serverHost, it) }
                Toast.makeText(context, "Recovery phrase saved to text file", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val pdfExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Unable to open destination file")
                outputStream.use { generateRecoveryPdf(words, serverHost, it) }
                Toast.makeText(context, "Printable recovery PDF generated", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "PDF generation error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
            ) {
                // Playful Registration Progress Bar (Step 2 of 4)
                RegistrationProgressBar(
                    currentStep = 2,
                    totalSteps = 4,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Recovery Phrase",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Write these words down on paper or export them to an offline storage drive. Never store unencrypted recovery words online.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(20.dp))

                    // Numbered 24-Word Grid (3 columns x 8 rows)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val colCount = 3
                        val rowCount = (words.size + colCount - 1) / colCount
                        for (rowIndex in 0 until rowCount) {
                            val firstIndex = rowIndex * colCount
                            val secondIndex = firstIndex + 1
                            val thirdIndex = firstIndex + 2

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (firstIndex < words.size) {
                                    WordGridCell(
                                        index = firstIndex,
                                        word = words[firstIndex],
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }

                                if (secondIndex < words.size) {
                                    WordGridCell(
                                        index = secondIndex,
                                        word = words[secondIndex],
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }

                                if (thirdIndex < words.size) {
                                    WordGridCell(
                                        index = thirdIndex,
                                        word = words[thirdIndex],
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Recovery Phrase Display Field for Password Manager Autofill
                    OutlinedTextField(
                        value = words.joinToString(" "),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Master Recovery Phrase") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Password },
                        shape = RoundedCornerShape(12.dp),
                        minLines = 2,
                        maxLines = 3
                    )

                    Spacer(Modifier.height(20.dp))

                    // Export Options Section
                    Text(
                        text = "Backup & Export Options",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Export Plaintext File (.txt) with TextSnippet Icon
                        FilledTonalButton(
                            onClick = { txtExportLauncher.launch("kosh-recovery-phrase.txt") },
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.TextSnippet,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Export Document (.txt)", fontWeight = FontWeight.SemiBold)
                        }

                        // Export Printable Paper Wallet (.pdf) with PictureAsPdf Icon
                        FilledTonalButton(
                            onClick = { pdfExportLauncher.launch("kosh-paper-wallet.pdf") },
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Printable Paper Wallet (.pdf)", fontWeight = FontWeight.SemiBold)
                        }

                        // Secure Clipboard Copy with CopyAll Icon
                        OutlinedButton(
                            onClick = { showClipboardAlert = true },
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CopyAll,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Copy to Clipboard", fontWeight = FontWeight.Medium)
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Acknowledgment Checkbox Barrier
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { hasAcknowledged = !hasAcknowledged }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = hasAcknowledged,
                                onCheckedChange = { hasAcknowledged = it }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "I have securely saved my 24-word recovery phrase in offline cold storage.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Primary Action Button: Proceed to Vault
                    Button(
                        onClick = onProceed,
                        enabled = hasAcknowledged,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Proceed to Vault",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }

            // Simplified Clipboard Security Warning Dialog
            if (showClipboardAlert) {
                AlertDialog(
                    onDismissRequest = { showClipboardAlert = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                    },
                    title = {
                        Text(
                            text = "Clipboard Alert",
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    },
                    text = {
                        Text(
                            text = "Keyboards and clipboard managers can read copied text. Only copy if you are pasting directly into a secure password manager.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(words.joinToString(" ")))
                                showClipboardAlert = false
                                Toast.makeText(context, "Recovery phrase copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text("Copy Anyway", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showClipboardAlert = false },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text("Cancel", fontWeight = FontWeight.Medium)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun WordGridCell(
    index: Int,
    word: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "%02d.".format(index + 1),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = word,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * Writes the 24-word recovery phrase formatted with timestamp and zero-knowledge disclaimer to an [OutputStream].
 */
private fun writePlainTextRecoveryFile(
    words: List<String>,
    serverHost: String,
    outputStream: OutputStream
) {
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    val content = buildString {
        appendLine("==================================================================")
        appendLine("                  KOSH VAULT — RECOVERY PHRASE                    ")
        appendLine("==================================================================")
        appendLine()
        appendLine("SECURITY NOTICE:")
        appendLine("This 24-word recovery phrase is the ONLY master key to unwrap your vault.")
        appendLine("The Kosh server operates under a Zero-Knowledge model and does not store")
        appendLine("your password or master recovery key. If lost, your vault cannot be recovered.")
        appendLine()
        appendLine("Server Endpoint: $serverHost")
        appendLine("Generated:       $dateStr")
        appendLine()
        appendLine("------------------------------------------------------------------")
        appendLine("RECOVERY MNEMONIC (24 WORDS)")
        appendLine("------------------------------------------------------------------")
        words.chunked(4).forEachIndexed { chunkIndex, chunk ->
            val lineWords = chunk.mapIndexed { i, w ->
                val num = chunkIndex * 4 + i + 1
                "[%02d] %-12s".format(num, w)
            }.joinToString("  ")
            appendLine(lineWords)
        }
        appendLine("------------------------------------------------------------------")
        appendLine()
        appendLine("Full Phrase:")
        appendLine(words.joinToString(" "))
        appendLine()
        appendLine("==================================================================")
    }
    outputStream.write(content.toByteArray(Charsets.UTF_8))
    outputStream.flush()
}

/**
 * Generates an A4 printable paper wallet PDF containing the 24 recovery words in a structured table.
 */
fun generateRecoveryPdf(
    words: List<String>,
    serverHost: String,
    outputStream: OutputStream
) {
    val document = PdfDocument()
    try {
        // Standard A4 dimensions: 595 x 842 points (72 DPI)
        val pageWidth = 595
        val pageHeight = 842
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val paint = Paint().apply { isAntiAlias = true }

        // Top Header Banner
        paint.color = Color.rgb(21, 25, 34)
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 90f, paint)

        // Header Title
        paint.color = Color.WHITE
        paint.textSize = 20f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("KOSH VAULT — COLD STORAGE PAPER WALLET", 40f, 50f, paint)

        paint.textSize = 10.5f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = Color.rgb(199, 201, 255)
        canvas.drawText("Zero-Knowledge 24-Word Master Recovery Key Document", 40f, 72f, paint)

        // Security Warning Box
        paint.color = Color.rgb(254, 243, 199)
        val warningRect = android.graphics.RectF(40f, 115f, pageWidth - 40f, 185f)
        canvas.drawRoundRect(warningRect, 8f, 8f, paint)

        paint.color = Color.rgb(180, 83, 9)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        canvas.drawRoundRect(warningRect, 8f, 8f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(146, 64, 14)
        paint.textSize = 11f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("CRITICAL SECURITY INSTRUCTIONS", 55f, 137f, paint)

        paint.textSize = 9.5f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = Color.rgb(120, 53, 15)
        canvas.drawText("Store this document in an offline, physically secure location (such as a fireproof safe).", 55f, 155f, paint)
        canvas.drawText("These 24 words represent your Master Vault Key. The Kosh server does not have access to your key.", 55f, 171f, paint)

        // Metadata
        paint.color = Color.rgb(100, 116, 139)
        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        canvas.drawText("Server Endpoint: $serverHost", 40f, 215f, paint)
        canvas.drawText("Generated: $dateStr", 40f, 230f, paint)

        // Divider Line
        paint.color = Color.rgb(226, 232, 240)
        paint.strokeWidth = 1f
        canvas.drawLine(40f, 245f, pageWidth - 40f, 245f, paint)

        // 24 Words Grid: 6 rows x 4 columns
        val gridStartX = 40f
        val gridStartY = 270f
        val cellWidth = (pageWidth - 80f) / 4f
        val cellHeight = 44f

        for (i in words.indices) {
            val row = i / 4
            val col = i % 4
            val x = gridStartX + col * cellWidth
            val y = gridStartY + row * cellHeight

            // Cell background
            paint.color = if (row % 2 == 0) Color.rgb(248, 250, 252) else Color.rgb(241, 245, 249)
            paint.style = Paint.Style.FILL
            val cellRect = android.graphics.RectF(x + 2f, y + 2f, x + cellWidth - 2f, y + cellHeight - 2f)
            canvas.drawRoundRect(cellRect, 6f, 6f, paint)

            // Cell border
            paint.color = Color.rgb(226, 232, 240)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawRoundRect(cellRect, 6f, 6f, paint)

            // Word number
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(79, 70, 229)
            paint.textSize = 10f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val numStr = "%02d.".format(i + 1)
            canvas.drawText(numStr, x + 10f, y + 27f, paint)

            // Word text
            paint.color = Color.rgb(15, 23, 42)
            paint.textSize = 12f
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText(words[i], x + 34f, y + 27f, paint)
        }

        // Recovery Instructions Box
        val numRows = (words.size + 3) / 4
        val instructStartY = gridStartY + numRows * cellHeight + 35f
        paint.color = Color.rgb(15, 23, 42)
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Recovery Instructions", 40f, instructStartY, paint)

        paint.color = Color.rgb(71, 85, 105)
        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val instructions = listOf(
            "1. Never enter these words on unverified devices, untrusted websites, or unexpected prompts.",
            "2. Do not upload photographs of this sheet to cloud services or photo backup applications.",
            "3. To restore access to your vault, select 'Recover with Passphrase' during Kosh sign-in.",
            "4. Enter all 24 words in the exact numbered order shown above, separated by single spaces."
        )
        var curY = instructStartY + 20f
        for (line in instructions) {
            canvas.drawText(line, 40f, curY, paint)
            curY += 18f
        }

        // Confidential Footer
        paint.color = Color.rgb(148, 163, 184)
        paint.textSize = 8.5f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        canvas.drawText("Kosh Zero-Knowledge Cloud Storage — Highly Confidential Cold Storage Key", 40f, pageHeight - 35f, paint)

        document.finishPage(page)
        document.writeTo(outputStream)
    } finally {
        document.close()
    }
}
