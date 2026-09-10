package com.scorecast.app

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch

/** Settings — account status, update check, and local-data management. Not in the original spec
 *  (Appendix B1 only calls for the gear icon); scoped from a conversation with the owner. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        TextButton(onClick = onBack) { Text("← Home") }
        Spacer(Modifier.height(4.dp))
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        SettingsSection("Account") { FacebookAccountRow() }
        Spacer(Modifier.height(24.dp))

        SettingsSection("Updates") { UpdatesRow() }
        Spacer(Modifier.height(24.dp))

        SettingsSection("Data") {
            ClearRecentTargetsRow(context)
            Spacer(Modifier.height(12.dp))
            DeleteAllMatchesRow(context)
            Spacer(Modifier.height(12.dp))
            DeleteAllRecordingsRow(context)
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun FacebookAccountRow() {
    val activity = LocalContext.current as ComponentActivity
    val authState by FacebookAuthManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var userName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authState) {
        val logged = authState as? FacebookAuthManager.State.LoggedIn
        userName = if (logged != null) {
            runCatching { FacebookGraphApi.getCurrentUserName(logged.accessToken) }.getOrNull()
        } else null
    }

    when (authState) {
        is FacebookAuthManager.State.LoggedIn -> {
            Text(
                if (userName != null) "Signed in as $userName" else "Signed in with Facebook",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { FacebookAuthManager.logout() }) { Text("Sign out") }
        }
        is FacebookAuthManager.State.LoggingIn -> {
            CircularProgressIndicator(modifier = Modifier.height(24.dp))
        }
        else -> {
            Text("Not signed in", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { scope.launch { FacebookAuthManager.login(activity) } }) {
                Text("Log in with Facebook")
            }
        }
    }
}

@Composable
private fun UpdatesRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var versionName by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }

    Text("Current version: $versionName", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))

    val update = updateInfo
    if (update != null) {
        Text("Update available: ${update.versionTag}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
                            if (file != null) context.startActivity(UpdateDownloader.installIntent(context, file))
                        }
                    }
                },
            ) { Text(if (downloading) "Downloading…" else "Update") }
            if (downloading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
            }
        }
    } else {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    checked = false
                    scope.launch {
                        updateInfo = UpdateChecker.checkForUpdate(versionName)
                        checking = false
                        checked = true
                    }
                },
            ) { Text(if (checking) "Checking…" else "Check for updates") }
            if (checking) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
            }
        }
        if (checked && !checking) {
            Spacer(Modifier.height(8.dp))
            Text("You're on the latest version.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClearRecentTargetsRow(context: android.content.Context) {
    var showConfirm by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }

    val count = remember(cleared) { RecentTargetsStore.list(context).size }

    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        // Disabled at zero: the dialog used to open and warn about permanently removing nothing.
        OutlinedButton(onClick = { showConfirm = true }, enabled = count > 0) {
            Text("Clear recent stream targets")
        }
        if (cleared) {
            Spacer(Modifier.width(8.dp))
            Text("Cleared", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Clear recent stream targets?") },
            // "ingest" is broadcast-engineer vocabulary; the persona here is a courtside operator.
            text = { Text("The quick-select list of $count recently-used streaming addresses will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        RecentTargetsStore.clear(context)
                        cleared = true
                        showConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeleteAllMatchesRow(context: android.content.Context) {
    var showConfirm by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }

    val count = remember(deleted) { MatchHistoryStore.list(context).size }

    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedButton(onClick = { showConfirm = true }, enabled = count > 0) {
            Text("Delete all match history")
        }
        if (deleted) {
            Spacer(Modifier.width(8.dp))
            Text("Deleted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            // Naming the count turns an abstract warning into an actual decision.
            title = { Text(if (count == 1) "Delete 1 match?" else "Delete all $count matches?") },
            text = { Text("Every saved match will be removed from your match history. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        MatchHistoryStore.deleteAll(context)
                        deleted = true
                        showConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeleteAllRecordingsRow(context: android.content.Context) {
    var showConfirm by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }

    // Irreplaceable footage, so this dialog states both how many files and how much video.
    val files = remember(deleted) { RecordingsManager.listRecordings(context) }
    val count = files.size
    val totalMb = remember(files) { files.sumOf { it.length() } / (1024.0 * 1024.0) }

    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedButton(onClick = { showConfirm = true }, enabled = count > 0) {
            Text("Delete all recordings")
        }
        if (deleted) {
            Spacer(Modifier.width(8.dp))
            Text("Deleted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = {
                Text(
                    if (count == 1) "Delete 1 recording?"
                    else "Delete all $count recordings?"
                )
            },
            text = {
                Text(
                    "Every saved video and photo on this device (%.1f MB) will be permanently deleted. This can't be undone."
                        .format(totalMb)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        RecordingsManager.deleteAll(context)
                        deleted = true
                        showConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } },
        )
    }
}
