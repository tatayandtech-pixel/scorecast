package com.scorecast.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Spec Appendix B2 — match history + entry point into a new session (portrait). */
@Composable
fun MatchesScreen(
    onBack: () -> Unit,
    onNewMatch: () -> Unit,
    onRematch: (MatchRecord) -> Unit,
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val matches = remember(refresh) { MatchHistoryStore.list(context) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        TextButton(onClick = onBack) { Text("← Home") }
        Spacer(Modifier.height(4.dp))
        Text("Matches", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Button(onClick = onNewMatch, modifier = Modifier.fillMaxWidth()) {
            Text("+ Stream new match")
        }
        Spacer(Modifier.height(20.dp))

        if (matches.isEmpty()) {
            Text(
                "No past matches yet — games you stream will show up here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(matches, key = { it.id }) { record ->
                    MatchCard(record = record, onClick = { onRematch(record) })
                }
            }
        }
    }
}

@Composable
private fun MatchCard(record: MatchRecord, onClick: () -> Unit) {
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(record.homeColorHex)
            Spacer(Modifier.width(6.dp))
            Text(record.homeTeam, style = MaterialTheme.typography.bodyMedium)
            Text(
                " vs ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(record.awayTeam, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
            ColorDot(record.awayColorHex)
            Spacer(Modifier.weight(1f))
            Text(
                dateFmt.format(Date(record.playedAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColorDot(hex: String) {
    val argb = try {
        android.graphics.Color.parseColor(hex)
    } catch (_: Exception) {
        android.graphics.Color.GRAY
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.size(12.dp).clip(CircleShape).background(Color(argb))
    )
}
