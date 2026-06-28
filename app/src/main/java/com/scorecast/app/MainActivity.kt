package com.scorecast.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SportConfigLoader.loadAll(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StreamScreen()
            }
        }
    }
}

@Composable
private fun StreamScreen() {
    val context = LocalContext.current
    val streamerState by StreamerHolder.state.collectAsState()
    val flip        by StreamerHolder.flip.collectAsState()
    val zoom        by StreamerHolder.zoomRatio.collectAsState()

    var ingestUrl     by rememberSaveable { mutableStateOf(StreamConfig.DEFAULT_INGEST_URL) }
    var streamKey     by rememberSaveable { mutableStateOf("") }
    var recordingMode by rememberSaveable { mutableStateOf(RecordingMode.STREAM_ONLY) }
    var showScorePanel by rememberSaveable { mutableStateOf(true) }
    var startRequested by remember { mutableStateOf(false) }

    val isLive = streamerState is StreamerHolder.State.Live || streamerState is StreamerHolder.State.Starting

    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = (result[Manifest.permission.CAMERA] == true || hasPermission(context, Manifest.permission.CAMERA)) &&
                 (result[Manifest.permission.RECORD_AUDIO] == true || hasPermission(context, Manifest.permission.RECORD_AUDIO))
        if (startRequested && ok) {
            startRequested = false
            launchStream(context, ingestUrl, streamKey, recordingMode)
        }
    }

    val onGoLive: () -> Unit = {
        if (hasPermission(context, Manifest.permission.CAMERA) &&
            hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            launchStream(context, ingestUrl, streamKey, recordingMode)
        } else {
            startRequested = true
            permissionLauncher.launch(permissions)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Camera always fills the screen so the SurfaceView is never recreated.
        // A float[] tag lets the pinch handler read the current zoom without
        // re-running the factory lambda.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    tag = floatArrayOf(zoom)
                    val scaleDetector = ScaleGestureDetector(ctx,
                        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            override fun onScale(d: ScaleGestureDetector): Boolean {
                                val cur = (tag as FloatArray)[0]
                                StreamerHolder.setZoomRatio(cur * d.scaleFactor)
                                return true
                            }
                        })
                    setOnTouchListener { v, e -> scaleDetector.onTouchEvent(e); v.performClick(); true }
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder)              { StreamerHolder.setPreviewSurface(h.surface) }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { StreamerHolder.setPreviewSurface(h.surface) }
                        override fun surfaceDestroyed(h: SurfaceHolder)            { StreamerHolder.setPreviewSurface(null) }
                    })
                }
            },
            update = { sv -> (sv.tag as FloatArray)[0] = zoom },
        )

        if (!isLive) {
            // ── SETUP mode: right-side panel with Scoring and Stream tabs ──────
            SetupPanel(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(380.dp)
                    .align(Alignment.CenterEnd)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                ingestUrl     = ingestUrl,
                streamKey     = streamKey,
                recordingMode = recordingMode,
                flip          = flip,
                zoom          = zoom,
                streamerState = streamerState,
                onIngestUrlChange     = { ingestUrl = it },
                onStreamKeyChange     = { streamKey = it },
                onRecordingModeChange = { recordingMode = it },
                onGoLive = onGoLive,
            )
        } else {
            // ── LIVE mode: fullscreen camera + floating controls ──────────────
            LiveOverlay(
                showScorePanel     = showScorePanel,
                onToggleScorePanel = { showScorePanel = !showScorePanel },
                streamerState      = streamerState,
                onStop             = { StreamingService.stop(context) },
            )
        }
    }
}

// ── Setup panel ──────────────────────────────────────────────────────────────

@Composable
private fun SetupPanel(
    modifier: Modifier = Modifier,
    ingestUrl: String,
    streamKey: String,
    recordingMode: RecordingMode,
    flip: StreamerHolder.Flip,
    zoom: Float,
    streamerState: StreamerHolder.State,
    onIngestUrlChange: (String) -> Unit,
    onStreamKeyChange: (String) -> Unit,
    onRecordingModeChange: (RecordingMode) -> Unit,
    onGoLive: () -> Unit,
) {
    val isLive = streamerState is StreamerHolder.State.Live || streamerState is StreamerHolder.State.Starting
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("ScoreCast", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 4.dp))

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                text = { Text("Scoring", fontSize = 12.sp) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                text = { Text("Stream", fontSize = 12.sp) })
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selectedTab == 0) {
                ScoringPanel()
                HorizontalDivider()
                LogoPanel()
                HorizontalDivider()
                RecordingsPanel()
            } else {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = ingestUrl, onValueChange = onIngestUrlChange,
                    label = { Text("RTMP(S) ingest URL") }, singleLine = true,
                    enabled = !isLive, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = streamKey, onValueChange = onStreamKeyChange,
                    label = { Text("Stream key") }, singleLine = true,
                    enabled = !isLive, modifier = Modifier.fillMaxWidth(),
                )
                RecordingModeRow(selected = recordingMode, enabled = !isLive, onSelect = onRecordingModeChange)
                HorizontalDivider()
                OrientationRow(flip = flip)
                ZoomRow(zoom = zoom)
            }
        }

        HorizontalDivider()
        Spacer(Modifier.height(6.dp))
        Text("Status: ${statusText(streamerState)}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Button(
            modifier = Modifier.fillMaxWidth(), enabled = !isLive,
            onClick = onGoLive,
        ) { Text("Go Live") }
    }
}

// ── Live overlay ─────────────────────────────────────────────────────────────

@Composable
private fun LiveOverlay(
    showScorePanel: Boolean,
    onToggleScorePanel: () -> Unit,
    streamerState: StreamerHolder.State,
    onStop: () -> Unit,
) {
    var showPairDialog by remember { mutableStateOf(false) }
    val isStarting = streamerState is StreamerHolder.State.Starting

    Box(Modifier.fillMaxSize()) {
        // Status badge (top-left)
        Text(
            text = if (isStarting) "● Starting…" else "● LIVE",
            color = if (isStarting) Color.Yellow else Color.Red,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )

        // Sliding scoring panel (right edge)
        AnimatedVisibility(
            visible = showScorePanel,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit  = slideOutHorizontally(targetOffsetX = { it }),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(340.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Scoring", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(
                        onClick = { showPairDialog = true },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(4.dp),
                    ) { Text("🔗 Pair device", fontSize = 11.sp) }
                }
                Spacer(Modifier.height(4.dp))
                ScoringPanel()
            }
        }

        // Floating action row (bottom-right)
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onToggleScorePanel,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(if (showScorePanel) "Hide panel" else "📊 Score", fontSize = 12.sp)
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(6.dp),
            ) { Text("⏹ Stop") }
        }
    }

    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            title = { Text("Pair a Scoring Device") },
            text  = {
                Text(
                    "QR-based remote scoring is coming in Phase 5 (Firebase sync).\n\n" +
                    "A second device will scan a QR code to open a scoring controller that " +
                    "updates scores, clock, and stats in real time while this device shows " +
                    "the full-screen broadcast view."
                )
            },
            confirmButton = {
                TextButton(onClick = { showPairDialog = false }) { Text("Got it") }
            },
        )
    }
}

// ── Shared control composables ───────────────────────────────────────────────

@Composable
private fun OrientationRow(flip: StreamerHolder.Flip) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = flip.horizontal,
            onCheckedChange = { StreamerHolder.setTransform(it, flip.vertical, flip.rotation) })
        Text("Mirror", fontSize = 11.sp)
        Switch(checked = flip.vertical,
            onCheckedChange = { StreamerHolder.setTransform(flip.horizontal, it, flip.rotation) })
        Text("Flip", fontSize = 11.sp)
    }
    OutlinedButton(onClick = {
        StreamerHolder.setTransform(flip.horizontal, flip.vertical, flip.rotation + 90)
    }) { Text("Rotate 90°  (${flip.rotation}°)", fontSize = 11.sp) }
}

@Composable
private fun ZoomRow(zoom: Float) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Zoom", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { StreamerHolder.setZoomRatio((zoom - 0.5f).coerceAtLeast(1f)) },
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) { Text("−", fontSize = 14.sp) }
        Text("%.1fx".format(zoom), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(40.dp))
        OutlinedButton(
            onClick = { StreamerHolder.setZoomRatio(zoom + 0.5f) },
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) { Text("+", fontSize = 14.sp) }
        OutlinedButton(
            onClick = { StreamerHolder.setZoomRatio(1f) },
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) { Text("1×", fontSize = 11.sp) }
    }
}

@Composable
private fun RecordingModeRow(selected: RecordingMode, enabled: Boolean, onSelect: (RecordingMode) -> Unit) {
    val labels = mapOf(
        RecordingMode.STREAM_ONLY      to "Stream",
        RecordingMode.STREAM_AND_RECORD to "Stream+Rec",
        RecordingMode.RECORD_ONLY      to "Rec only",
    )
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Mode", style = MaterialTheme.typography.bodySmall)
        RecordingMode.entries.forEach { mode ->
            OutlinedButton(
                onClick = { onSelect(mode) },
                enabled = enabled,
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                colors = if (selected == mode) ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) else ButtonDefaults.outlinedButtonColors(),
            ) { Text(labels[mode] ?: mode.name, fontSize = 10.sp) }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun launchStream(
    context: android.content.Context,
    ingestUrl: String,
    streamKey: String,
    mode: RecordingMode,
) {
    val file = if (mode != RecordingMode.STREAM_ONLY) RecordingsManager.newRecordingFile(context) else null
    StreamingService.start(context, ingestUrl, streamKey, mode, file)
}

private fun statusText(state: StreamerHolder.State): String = when (state) {
    StreamerHolder.State.Idle      -> "idle"
    StreamerHolder.State.Starting  -> "starting…"
    StreamerHolder.State.Live      -> "LIVE"
    is StreamerHolder.State.Error  -> "error — ${state.message}"
}

private fun hasPermission(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
