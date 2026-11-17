package project.kosh.feature.gallery.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Text input row with an action button for manually entering an 8-character pairing code.
 *
 * @param code Current string value entered in the text field.
 * @param onCodeChange Invoked when the text input changes.
 * @param onFulfill Invoked when the user confirms the entered code.
 * @param modifier Composable modifier applied to the row layout.
 */
@Composable
fun ManualPairingCodeInput(
    code: String,
    onCodeChange: (String) -> Unit,
    onFulfill: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("8-character code") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { onFulfill(code) },
            enabled = code.trim().replace("-", "").replace(" ", "").length == 8,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(48.dp)
        ) {
            Text("Link")
        }
    }
}

// Previews

@Preview(name = "Manual Code Input Light", showBackground = true)
@Composable
private fun ManualPairingCodeInputLightPreview() {
    GalleryPreviewTheme(darkTheme = false) {
        ManualPairingCodeInput(
            code = "ABCD1234",
            onCodeChange = {},
            onFulfill = {}
        )
    }
}

@Preview(name = "Manual Code Input Empty Dark", showBackground = true)
@Composable
private fun ManualPairingCodeInputEmptyDarkPreview() {
    GalleryPreviewTheme(darkTheme = true) {
        ManualPairingCodeInput(
            code = "",
            onCodeChange = {},
            onFulfill = {}
        )
    }
}
