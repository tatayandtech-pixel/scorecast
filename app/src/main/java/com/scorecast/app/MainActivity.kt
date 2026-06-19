package com.scorecast.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StreamScreen()
                }
            }
        }
    }
}

@Composable
private fun StreamScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by StreamerHolder.state.collectAsState()

    var ingestUrl by rememberSaveable { mutableStateOf(StreamConfig.DEFAULT_INGEST_URL) }
    var streamKey by rememberSaveable { mutableStateOf("") }

    // Pending start intent held until permissions resolve.
    var startRequested by remember { mutableStateOf(false) }

    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraOk = result[Manifest.permission.CAMERA] == true ||
            hasPermission(context, Manifest.permission.CAMERA)
        val micOk = result[Manifest.permission.RECORD_AUDIO] == true ||
            hasPermission(context, Manifest.permission.RECORD_AUDIO)
        if (startRequested && cameraOk && micOk) {
            startRequested = false
            StreamingService.start(context, ingestUrl, streamKey)
        }
    }

    Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Preview pane (operator view; same composited frame that is encoded).
        AndroidView(
            modifier = Modifier.fillMaxHeight().width(420.dp),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            StreamerHolder.setPreviewSurface(h.surface)
                        }

                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                            StreamerHolder.setPreviewSurface(h.surface)
                        }

                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            StreamerHolder.setPreviewSurface(null)
                        }
                    })
                }
            },
        )

        Spacer(Modifier.width(20.dp))

        Column(modifier = Modifier.fillMaxHeight().fillMaxWidth()) {
            Text("ScoreCast — Phase 1", style = MaterialTheme.typography.titleLarge)
            Text(
                "Static burned-in scoreboard → RTMP",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            val live = state is StreamerHolder.State.Live || state is StreamerHolder.State.Starting

            OutlinedTextField(
                value = ingestUrl,
                onValueChange = { ingestUrl = it },
                label = { Text("RTMP ingest URL") },
                singleLine = true,
                enabled = !live,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = streamKey,
                onValueChange = { streamKey = it },
                label = { Text("Stream key") },
                singleLine = true,
                enabled = !live,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = StreamConfig.USE_OVERLAY, enabled = false, onCheckedChange = {})
                Spacer(Modifier.width(8.dp))
                Text(
                    if (StreamConfig.USE_OVERLAY) "Overlay burn-in ON (compile-time)"
                    else "Overlay burn-in OFF (compile-time)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !live,
                    onClick = {
                        if (hasPermission(context, Manifest.permission.CAMERA) &&
                            hasPermission(context, Manifest.permission.RECORD_AUDIO)
                        ) {
                            StreamingService.start(context, ingestUrl, streamKey)
                        } else {
                            startRequested = true
                            permissionLauncher.launch(permissions)
                        }
                    },
                ) { Text("Go live") }

                Button(
                    enabled = live,
                    onClick = { StreamingService.stop(context) },
                ) { Text("Stop") }
            }

            Spacer(Modifier.height(20.dp))
            Text("Status: ${statusText(state)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun statusText(state: StreamerHolder.State): String = when (state) {
    StreamerHolder.State.Idle -> "idle"
    StreamerHolder.State.Starting -> "starting…"
    StreamerHolder.State.Live -> "LIVE"
    is StreamerHolder.State.Error -> "error — ${state.message}"
}

private fun hasPermission(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
