package com.scorecast.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Spec Appendix B5 — wizard step 3/4: named platform tiles + stream/record mode toggle. */
@Composable
fun WizardPlatformScreen(
    target: StreamTarget,
    onTargetChange: (StreamTarget) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    WizardScaffold(step = 3, title = "Choose the platform", onBack = onBack, onNext = onNext) {
        Column {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.height(280.dp),
            ) {
                items(Platform.entries.toList(), key = { it.name }) { platform ->
                    val selected = target.platform == platform
                    Box(
                        modifier = Modifier
                            .aspectRatio(1.6f)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(12.dp),
                            )
                            .clickable {
                                onTargetChange(
                                    target.copy(
                                        platform = platform,
                                        ingestUrl = platform.defaultIngestUrl,
                                        mode = when {
                                            platform == Platform.SAVE_IN_MEMORY -> RecordingMode.RECORD_ONLY
                                            target.mode == RecordingMode.RECORD_ONLY -> RecordingMode.STREAM_ONLY
                                            else -> target.mode
                                        },
                                    )
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(platform.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (target.platform != Platform.SAVE_IN_MEMORY) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = target.mode == RecordingMode.STREAM_AND_RECORD,
                        onCheckedChange = { checked ->
                            onTargetChange(
                                target.copy(
                                    mode = if (checked) RecordingMode.STREAM_AND_RECORD else RecordingMode.STREAM_ONLY
                                )
                            )
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Save the video on my device while I'm live streaming",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
