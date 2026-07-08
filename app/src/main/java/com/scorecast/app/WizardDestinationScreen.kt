package com.scorecast.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Spec Appendix B6 — wizard step 4/4: destination key entry, sponsor logos, break graphics (v2, disabled). */
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

    WizardScaffold(
        step = 4,
        title = "Destination & extras",
        onBack = onBack,
        onNext = {
            if (needsKey) RecentTargetsStore.record(context, target.platform, target.ingestUrl)
            onFinish()
        },
        nextEnabled = !needsKey || target.streamKey.isNotBlank(),
        nextLabel = "Finish",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (!needsKey) {
                Text(
                    "Recording only — no ingest server needed. The stream saves to this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text("Sponsor logos (${logos.size}/6)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            LogoPanel()

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
