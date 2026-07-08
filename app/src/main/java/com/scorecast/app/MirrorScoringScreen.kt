package com.scorecast.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Spec §9 step 4 — mirror device's scoring-only screen (no camera/video access, by design).
 * Local-first (spec §2): edits here apply to the local state immediately and sync to Firebase in
 * the background — this screen never blocks on the network, it just shows the connection state
 * alongside the same scoring UI the main device uses.
 */
@Composable
fun MirrorScoringScreen(session: SessionCode, onLeave: () -> Unit) {
    val connection by FirebaseSessionSync.connectionState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        TextButton(onClick = onLeave) { Text("← Leave session") }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        ScoringPanel()
    }
}

@Composable
private fun ConnectionDot(state: FirebaseSessionSync.ConnectionState) {
    val (color, label) = when (state) {
        FirebaseSessionSync.ConnectionState.Connected -> Color(0xFF2E7D32) to "Connected"
        FirebaseSessionSync.ConnectionState.Connecting -> Color(0xFFF9A825) to "Connecting…"
        FirebaseSessionSync.ConnectionState.Reconnecting -> Color(0xFFF9A825) to "Reconnecting…"
        FirebaseSessionSync.ConnectionState.Idle -> Color.Gray to "Idle"
        is FirebaseSessionSync.ConnectionState.Failed -> Color(0xFFC62828) to "Connection issue"
    }
    Text("● $label", color = color, style = MaterialTheme.typography.labelMedium)
}
