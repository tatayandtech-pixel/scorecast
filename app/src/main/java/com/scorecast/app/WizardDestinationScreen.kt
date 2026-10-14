package com.scorecast.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Spec Appendix B6 — wizard step 4/4: destination key entry, sponsor logos, the intro clip (beyond
 *  spec, owner request), break graphics (v2, disabled). */
@Composable
fun WizardDestinationScreen(
    target: StreamTarget,
    onTargetChange: (StreamTarget) -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val recents = remember(target.platform) {
        RecentTargetsStore.list(context).filter { it.platform == target.platform }
    }
    val logos by LogoHolder.logos.collectAsState()
    val needsKey = target.platform != Platform.SAVE_IN_MEMORY
    var useManual by remember(target.platform) { mutableStateOf(false) }
    val showManual = target.platform != Platform.FACEBOOK || useManual

    WizardScaffold(
        step = 4,
        title = "Destination & extras",
        onBack = onBack,
        onNext = {
            // Facebook's auto-created live video URL is one-time-use, so recording it into "Recent"
            // would only offer a stale, already-expired URL for reuse next time.
            if (needsKey && showManual) RecentTargetsStore.record(context, target.platform, target.ingestUrl)
            onFinish()
        },
        nextEnabled = when {
            !needsKey -> true
            !showManual -> target.ingestUrl.isNotBlank() && target.ingestUrl != Platform.FACEBOOK.defaultIngestUrl
            else -> target.streamKey.isNotBlank()
        },
        nextLabel = "Finish",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (!needsKey) {
                Text(
                    "Recording only — no ingest server needed. The stream saves to this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!showManual) {
                FacebookDestinationScreen(
                    target = target,
                    onTargetChange = onTargetChange,
                    onUseManual = { useManual = true },
                )
            } else {
                Text("${target.platform.displayName} ingest", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = target.ingestUrl,
                    onValueChange = { onTargetChange(target.copy(ingestUrl = it)) },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target.streamKey,
                    onValueChange = { onTargetChange(target.copy(streamKey = it)) },
                    label = { Text("Stream key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (recents.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Recent",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    recents.forEach { r ->
                        Text(
                            r.ingestUrl,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onTargetChange(target.copy(ingestUrl = r.ingestUrl)) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Video quality", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoQuality.entries.forEach { q ->
                    val selected = target.quality == q
                    OutlinedButton(
                        onClick = { onTargetChange(target.copy(quality = q)) },
                        modifier = Modifier.height(44.dp).semantics { this.selected = selected },
                        colors = if (selected) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        ) else ButtonDefaults.outlinedButtonColors(),
                    ) { Text(q.label, fontSize = 12.sp) }
                }
            }
            Text(
                // There's no automatic step-down mid-stream, so say up front what High needs.
                if (target.quality == VideoQuality.HIGH) {
                    "Sharpest picture. Needs a strong, steady upload (about 8 Mbps) — use Standard on weak venue Wi-Fi or mobile data."
                } else {
                    "Steadier on weak venue Wi-Fi or mobile data."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Sponsor logos (${logos.size}/6)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            LogoPanel()

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            IntroClipPanel()

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "Break graphics — coming later (v2)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}
