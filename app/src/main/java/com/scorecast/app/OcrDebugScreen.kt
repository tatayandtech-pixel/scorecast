package com.scorecast.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scorecast.app.ocr.OcrCameraController
import com.scorecast.app.ocr.OcrScannerView

/**
 * DEBUG BUILDS ONLY — opens the OCR camera scanner without going through the two-device pairing
 * flow.
 *
 * In a normal build the only way to reach the scanner is Home -> "Join as remote scorer" ->
 * scan the main device's pairing QR -> "Remote Scoring" -> "OCR Scan", which needs a second
 * phone running the main stream and a live Firebase session. That makes the OCR pipeline
 * impossible to exercise on a single device, so debug builds get this direct entry point.
 *
 * It deliberately does NOT talk to Firebase: the detected deltas are only reported on screen, so
 * nothing is written to a live scoreboard from a debug session.
 */
@Composable
fun OcrDebugScreen(onBack: () -> Unit) {
    var detected by remember { mutableStateOf("—") }

    // The matcher needs a current game state and a sport config to interpret the OCR text.
    val sportConfig = remember { SportConfigLoader.getCached("basketball") }
    val debugState = remember { GameState(sport = "basketball") }

    LaunchedEffect(Unit) {
        OcrCameraController.currentGameState = debugState
        OcrCameraController.currentSportConfig = sportConfig
    }
    DisposableEffect(Unit) {
        onDispose {
            OcrCameraController.currentGameState = null
            OcrCameraController.currentSportConfig = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) { Text("← Home") }
            Text("OCR debug", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = "Detected: $detected",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        OcrScannerView(
            modifier = Modifier.fillMaxSize(),
            onScoreDelta = { delta ->
                detected = "HOME +${delta.homeDelta} / AWAY +${delta.awayDelta}"
            },
        )
    }
}
