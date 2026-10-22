package com.scorecast.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.scorecast.app.ocr.OcrCameraController
import com.scorecast.app.ocr.OcrScannerView
import com.scorecast.app.ocr.OcrScoreMatcher.ScoreDelta
import com.scorecast.app.theme.ScoreCastStatusOk
import com.scorecast.app.theme.ScoreCastStatusPending

/**
 * Spec §9 step 4 — mirror device's scoring-only screen (no camera/video access, by design).
 * Local-first (spec §2): edits here apply to the local state immediately and sync to Firebase in
 * the background — this screen never blocks on the network, it just shows the connection state
 * alongside the same scoring UI the main device uses.
 *
 * Optional OCR Scan mode: tap the camera icon to switch to camera-based scoring. Point the
 * phone's camera at a physical basketball scoreboard; Tesseract reads the digits and pushes
 * detected score changes as atomic increments via the same Firebase path as manual taps.
 */
@Composable
fun MirrorScoringScreen(session: SessionCode, onLeave: () -> Unit) {
    val connection by FirebaseSessionSync.connectionState.collectAsState()
    val gameState by GameStateHolder.state.collectAsState()

    // Pre-load the sport config so the OCR matcher knows which fields to look for.
    val sportConfig = remember(gameState.sport) {
        SportConfigLoader.getCached(gameState.sport)
    }

    // Sync current state into the OCR controller whenever it changes.
    LaunchedEffect(Unit) {
        OcrCameraController.currentGameState = gameState
        OcrCameraController.currentSportConfig = sportConfig
    }
    DisposableEffect(gameState) {
        OcrCameraController.currentGameState = gameState
        onDispose { /* nothing */ }
    }

    var ocrMode by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onLeave) { Text("← Leave session") }
            if (ocrMode) {
                TextButton(onClick = { ocrMode = false }) { Text("Exit OCR") }
            } else {
                TextButton(onClick = { ocrMode = true }) { Text("OCR Scan") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Remote Scoring", style = MaterialTheme.typography.headlineSmall)
            ConnectionDot(connection)
        }
        Spacer(Modifier.height(8.dp))
        if (connection is FirebaseSessionSync.ConnectionState.Failed) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Reconnecting — your edits are kept locally and will sync once the " +
                        "connection is back.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (ocrMode) {
            OcrScannerView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onScoreDelta = { delta -> applyOcrScoreDelta(delta) },
            )
        } else {
            ScoringPanel()
        }
    }
}

/** Applies an OCR-detected score delta to the local GameStateHolder, which pushes the
 *  change to Firebase via the existing observeLocalChanges() path (same as manual taps). */
private fun applyOcrScoreDelta(delta: ScoreDelta) {
    if (delta.homeDelta != 0 || delta.awayDelta != 0) {
        GameStateHolder.update {
            copy(
                homeScore = (homeScore + delta.homeDelta).coerceAtLeast(0),
                awayScore = (awayScore + delta.awayDelta).coerceAtLeast(0),
            )
        }
    }
}

@Composable
private fun ConnectionDot(state: FirebaseSessionSync.ConnectionState) {
    val (color, label) = when (state) {
        FirebaseSessionSync.ConnectionState.Connected -> ScoreCastStatusOk() to "Connected"
        FirebaseSessionSync.ConnectionState.Connecting -> ScoreCastStatusPending() to "Connecting…"
        FirebaseSessionSync.ConnectionState.Reconnecting -> ScoreCastStatusPending() to "Reconnecting…"
        FirebaseSessionSync.ConnectionState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant to "Idle"
        is FirebaseSessionSync.ConnectionState.Failed -> MaterialTheme.colorScheme.error to "Connection issue"
    }
    Text("● $label", color = color, style = MaterialTheme.typography.labelMedium)
}
