package com.scorecast.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch

/** Spec Appendix B1 — app launch screen (portrait). */
@Composable
fun HomeScreen(
    onCreateStream: () -> Unit,
    onJoinAsRemote: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        updateInfo = UpdateChecker.checkForUpdate(current)
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ScoreCast", style = MaterialTheme.typography.headlineMedium)
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.semantics { contentDescription = "Settings" },
            ) {
                Text("⚙", fontSize = 22.sp)
            }
        }

        val update = updateInfo
        if (update != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Update available: ${update.versionTag}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = !downloading,
                            onClick = {
                                if (!context.packageManager.canRequestPackageInstalls()) {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            "package:${context.packageName}".toUri(),
                                        )
                                    )
                                } else {
                                    downloading = true
                                    scope.launch {
                                        val file = UpdateDownloader.downloadApk(
                                            context, update.downloadUrl, "scorecast_${update.versionTag}.apk"
                                        )
                                        downloading = false
                                        if (file != null) {
                                            context.startActivity(UpdateDownloader.installIntent(context, file))
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(if (downloading) "Downloading…" else "Update")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { updateInfo = null }) { Text("Dismiss") }
                        if (downloading) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
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
