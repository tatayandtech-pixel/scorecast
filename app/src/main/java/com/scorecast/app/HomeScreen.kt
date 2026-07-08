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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Spec Appendix B1 — app launch screen (portrait). */
@Composable
fun HomeScreen(
    onCreateStream: () -> Unit,
    onJoinAsRemote: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ScoreCast", style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = { /* settings — not yet built */ }) {
                Text("⚙", fontSize = 22.sp)
            }
        }

        HomeCard(
            title = "Create a new live stream",
            subtitle = "— and score yourself",
            onClick = onCreateStream,
        )
        Spacer(Modifier.height(16.dp))
        HomeCard(
            title = "Join as remote scorer",
            subtitle = "Scan the main device's pairing QR code",
            onClick = onJoinAsRemote,
        )
    }
}

@Composable
private fun HomeCard(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
