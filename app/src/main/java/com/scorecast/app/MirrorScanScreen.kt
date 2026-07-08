package com.scorecast.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Spec §9 step 3 — mirror device scans the main device's QR, then validates the joinToken against
 * Firebase (spec §4) before binding, so a stray or expired scan can't hijack the scoreboard.
 */
@Composable
fun MirrorScanScreen(
    onBack: () -> Unit,
    onJoined: (SessionCode) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw != null) {
            val code = SessionCode.parse(raw)
            if (code == null) {
                error = "That QR isn't a ScoreCast pairing code."
            } else {
                joining = true
                error = null
                FirebaseSessionSync.joinAsMirror(code) { success ->
                    joining = false
                    if (success) onJoined(code) else error = "Couldn't join — the code may be expired."
                }
            }
        }
        // raw == null means the operator backed out of the scanner — just stay on this screen.
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack, enabled = !joining) { Text("← Home") }
        Spacer(Modifier.height(24.dp))
        Text("Join as remote scorer", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Scan the QR code shown on the main device's in-game screen " +
                "(tap \"Pair device\" there first).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        if (error != null) {
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
        }
        if (joining) {
            Text("Joining session…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(
                onClick = {
                    error = null
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the main device's pairing QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan QR code") }
        }
    }
}
