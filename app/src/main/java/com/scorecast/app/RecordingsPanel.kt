package com.scorecast.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsPanel() {
    val context = LocalContext.current
    // Re-scan on each composition (list is short, scan is fast).
    var refresh by remember { mutableStateOf(0) }
    val recordings = remember(refresh) { RecordingsManager.listRecordings(context) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Text("Recordings", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = { refresh++ },
                modifier = Modifier.height(44.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(4.dp),
            ) { Text("Refresh", fontSize = 11.sp) }
        }

        if (recordings.isEmpty()) {
            Text("No recordings yet", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp))
        }

        recordings.forEach { file ->
            RecordingRow(
                file = file,
                onPlay = { context.startActivity(RecordingsManager.playIntent(context, file)) },
                onShare = { context.startActivity(RecordingsManager.shareIntent(context, file)) },
                onDelete = { RecordingsManager.deleteRecording(file); refresh++ },
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun RecordingRow(file: File, onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.US) }
    val sizeMb = "%.1f MB".format(file.length() / 1_048_576.0)
    val date = dateFmt.format(Date(file.lastModified()))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text("$date  ·  $sizeMb", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(4.dp))
        SmallRecordingButton("▶", contentDescription = "Play ${file.name}") { onPlay() }
        Spacer(Modifier.width(2.dp))
        SmallRecordingButton("Share", contentDescription = "Share ${file.name}") { onShare() }
        Spacer(Modifier.width(2.dp))
        SmallRecordingButton("Delete", contentDescription = "Delete ${file.name}") { showDeleteConfirm = true }
    }

    // A recording is irreplaceable footage of a game that already finished, yet this was the one
    // delete in the app that fired instantly — while deleting a row of regenerable match metadata
    // got a full confirm dialog. Same dialog pattern as MatchesScreen, naming the file and size.
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this recording?") },
            text = { Text("${file.name} ($sizeMb) will be permanently deleted from this device. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SmallRecordingButton(
    label: String,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        // "▶" announces as a symbol name or nothing, and "Del" was a truncation; every one of these
        // now carries a real name, matching ScoringPanel's SmallButton.
        modifier = Modifier
            .height(44.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        shape = RoundedCornerShape(4.dp),
    ) { Text(label, fontSize = 10.sp) }
}
