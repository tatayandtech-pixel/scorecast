package com.scorecast.app

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
                AppRoot()
            }
        }
    }
}

/** Navigation skeleton (spec Appendix B/A): portrait setup screens, landscape in-game screen. */
private enum class Screen {
    HOME, MATCHES, WIZARD_SPORT, WIZARD_TEAMS, WIZARD_PLATFORM, WIZARD_DESTINATION, IN_GAME,
    MIRROR_SCAN, MIRROR_SCORING,
}

@Composable
private fun AppRoot() {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var pendingTarget by remember { mutableStateOf(StreamTarget()) }
    var mirrorSession by remember { mutableStateOf<SessionCode?>(null) }
    val activity = LocalContext.current as? Activity

    // Setup flow (Home, Matches, the wizard, the mirror) is portrait; the in-game screen is
    // landscape (spec Appendix B "Orientation"). configChanges in the manifest keeps the Activity
    // (and camera/StreamerHolder state) alive across this switch instead of recreating it.
    LaunchedEffect(screen) {
        activity?.requestedOrientation = when (screen) {
            Screen.IN_GAME -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onCreateStream = { screen = Screen.MATCHES },
            onJoinAsRemote = { screen = Screen.MIRROR_SCAN },
        )
        Screen.MIRROR_SCAN -> MirrorScanScreen(
            onBack = { screen = Screen.HOME },
            onJoined = { code ->
                mirrorSession = code
                screen = Screen.MIRROR_SCORING
            },
        )
        Screen.MIRROR_SCORING -> {
            val session = mirrorSession
            if (session != null) {
                MirrorScoringScreen(
                    session = session,
                    onLeave = {
                        FirebaseSessionSync.stop()
                        mirrorSession = null
                        screen = Screen.HOME
                    },
                )
            } else {
                // Shouldn't happen via normal navigation (no session survives process death yet) —
                // bounce back rather than crash on a null session.
                LaunchedEffect(Unit) { screen = Screen.HOME }
            }
        }
        Screen.MATCHES -> MatchesScreen(
            onBack = { screen = Screen.HOME },
            onNewMatch = {
                pendingTarget = StreamTarget()
                screen = Screen.WIZARD_SPORT
            },
            onRematch = { record ->
                GameStateHolder.update {
                    copy(
                        sport = record.sport,
                        homeTeam = record.homeTeam,
                        awayTeam = record.awayTeam,
                        homeColorHex = record.homeColorHex,
                        awayColorHex = record.awayColorHex,
                    )
                }
                pendingTarget = StreamTarget()
                screen = Screen.WIZARD_SPORT
            },
        )
        Screen.WIZARD_SPORT -> WizardSportScreen(
            onBack = { screen = Screen.MATCHES },
            onNext = { screen = Screen.WIZARD_TEAMS },
        )
        Screen.WIZARD_TEAMS -> WizardTeamsScreen(
            onBack = { screen = Screen.WIZARD_SPORT },
            onNext = { screen = Screen.WIZARD_PLATFORM },
        )
        Screen.WIZARD_PLATFORM -> WizardPlatformScreen(
            target = pendingTarget,
            onTargetChange = { pendingTarget = it },
            onBack = { screen = Screen.WIZARD_TEAMS },
            onNext = { screen = Screen.WIZARD_DESTINATION },
        )
        Screen.WIZARD_DESTINATION -> WizardDestinationScreen(
            target = pendingTarget,
            onTargetChange = { pendingTarget = it },
            onBack = { screen = Screen.WIZARD_PLATFORM },
            onFinish = { screen = Screen.IN_GAME },
        )
        Screen.IN_GAME -> StreamScreen(
            onExit = { screen = Screen.MATCHES },
            initialTarget = pendingTarget,
        )
    }
}

@Composable
private fun StreamScreen(onExit: () -> Unit, initialTarget: StreamTarget = StreamTarget()) {
    val context = LocalContext.current
    val streamerState by StreamerHolder.state.collectAsState()
    val flip        by StreamerHolder.flip.collectAsState()
    val zoom        by StreamerHolder.zoomRatio.collectAsState()

    var ingestUrl     by rememberSaveable { mutableStateOf(initialTarget.ingestUrl) }
    var streamKey     by rememberSaveable { mutableStateOf(initialTarget.streamKey) }
    var recordingMode by rememberSaveable { mutableStateOf(initialTarget.mode) }
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
                onExit = onExit,
            )
        } else {
            // ── LIVE mode: fullscreen camera + floating controls ──────────────
            LiveOverlay(
                showScorePanel     = showScorePanel,
                onToggleScorePanel = { showScorePanel = !showScorePanel },
                streamerState      = streamerState,
                onStop             = {
                    StreamingService.stop(context)
                    // Spec Appendix A "End match": save the session to local history.
                    MatchHistoryStore.save(context, MatchRecord.from(GameStateHolder.state.value))
                    // Fresh pairing session (and QR) for the next match.
                    SessionHolder.clear()
                    onExit()
                },
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
    onExit: () -> Unit,
) {
    val isLive = streamerState is StreamerHolder.State.Live || streamerState is StreamerHolder.State.Starting
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Text("ScoreCast", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp))
            TextButton(onClick = onExit) { Text("← Matches", fontSize = 12.sp) }
        }

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
    val context = LocalContext.current
    var showPairDialog by remember { mutableStateOf(false) }
    var showEndMatchConfirm by remember { mutableStateOf(false) }
    var showMenuStub by remember { mutableStateOf(false) }
    var micMuted by remember { mutableStateOf(false) }
    val isStarting = streamerState is StreamerHolder.State.Starting
    val isRecording = remember(streamerState) { StreamerHolder.currentRecordingFile != null }
    val batteryPct = rememberBatteryPercent()

    Box(Modifier.fillMaxSize()) {
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
                    // Bottom inset reserves clearance for the floating bottom-right action row
                    // (battery/Hide panel/End match), which is drawn on top of this panel — see
                    // below. Without it, on shorter screens the scrollable viewport extends
                    // underneath those buttons, so the panel's last rows (Period/Clock) land
                    // behind them and lose both their display and their touch targets to the
                    // floating row's.
                    .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 64.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Clears the top bar (health/recording chips, remote-scoring toggle, mic/share/
                // overflow) drawn on top of this panel — see below. The panel's own pairing
                // entry point was removed: the top bar's remote-scoring toggle covers it now.
                Spacer(Modifier.height(40.dp))
                Text("Scoring", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                ScoringPanel()
            }
        }

        // Top bar (spec Appendix A): health chip + recording indicator on the left,
        // remote-scoring/scorer-status stub, mic toggle, share, and overflow on the right.
        // Declared after the sliding panel so its icons stay on top and clickable while the
        // panel is open, instead of being covered by it.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(
                    text = if (isStarting) "● Starting…" else "● LIVE",
                    color = if (isStarting) Color.Yellow else Color.Red,
                )
                Chip(
                    text = if (isRecording) "● Recording" else "○ Not being stored",
                    color = if (isRecording) Color.Red else Color.Gray,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Remote-scoring toggle + scorer-connection dot (spec §9), backed by Firebase presence.
                val session by SessionHolder.session.collectAsState()
                val mirrorConnected by FirebaseSessionSync.mirrorConnected.collectAsState()
                Switch(checked = session != null, onCheckedChange = { showPairDialog = true },
                    modifier = Modifier.height(20.dp))
                Chip(
                    text = if (mirrorConnected) "● scorer connected" else "● scorer offline",
                    color = if (mirrorConnected) Color(0xFF2E7D32) else Color.Gray,
                )
                TopBarIconButton(if (micMuted) "🔇" else "🎤") {
                    micMuted = !micMuted
                    (context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager)
                        ?.isMicrophoneMute = micMuted
                }
                TopBarIconButton("📤") {
                    context.startActivity(
                        android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    "Live now on ScoreCast: ${GameStateHolder.state.value.homeTeam} vs " +
                                        "${GameStateHolder.state.value.awayTeam}",
                                )
                            },
                            "Share",
                        )
                    )
                }
                TopBarIconButton("⋮") { showMenuStub = true }
            }
        }

        // Floating action row (bottom-right) — spec Appendix A bottom bar (battery + end match).
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔋 $batteryPct%", style = MaterialTheme.typography.labelMedium)
            OutlinedButton(
                onClick = onToggleScorePanel,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(if (showScorePanel) "Hide panel" else "📊 Score", fontSize = 12.sp)
            }
            Button(
                onClick = { showEndMatchConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(6.dp),
            ) { Text("⏹ End match") }
        }
    }

    if (showPairDialog) {
        val session = remember { SessionHolder.ensureSession() }
        val qrBitmap = remember(session) { QrCodeUtil.generate(session.toQrPayload()).asImageBitmap() }
        val clipboard = LocalClipboardManager.current
        val pairingCode = "${session.sessionId}:${session.joinToken}"
        var copied by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            title = { Text("Pair a Scoring Device") },
            text = {
                // Row, not Column: this dialog only ever shows on the landscape in-game screen,
                // which is short on height but has plenty of width — a vertically-stacked layout
                // pushed the code/copy button below the visible dialog bounds.
                Row(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = "Pairing QR code",
                        modifier = Modifier.size(130.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "Android: scan this code. iPhone/laptop: use the web mirror with the code below.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            pairingCode,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(pairingCode))
                            copied = true
                        }) { Text(if (copied) "Copied!" else "Copy code") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPairDialog = false }) { Text("Done") }
            },
        )
    }

    if (showMenuStub) {
        AlertDialog(
            onDismissRequest = { showMenuStub = false },
            title = { Text("Settings") },
            text = { Text("Settings screen isn't built yet — coming in a later phase.") },
            confirmButton = { TextButton(onClick = { showMenuStub = false }) { Text("OK") } },
        )
    }

    if (showEndMatchConfirm) {
        AlertDialog(
            onDismissRequest = { showEndMatchConfirm = false },
            title = { Text("End this match?") },
            text = { Text("This stops the stream/recording and saves the result to Matches.") },
            confirmButton = {
                TextButton(onClick = { showEndMatchConfirm = false; onStop() }) { Text("End match") }
            },
            dismissButton = {
                TextButton(onClick = { showEndMatchConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun TopBarIconButton(label: String, onClick: () -> Unit) {
    // Fixed-size Box instead of sizing off the glyph's own intrinsic bounds — some glyphs (e.g.
    // "⋮") measure far narrower than they look, which was shrinking the real tap target down to
    // a sliver despite the visible background looking full-size.
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Battery percentage, refreshed via the sticky ACTION_BATTERY_CHANGED broadcast. */
@Composable
private fun rememberBatteryPercent(): Int {
    val context = LocalContext.current
    var percent by remember { mutableIntStateOf(100) }
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) percent = (level * 100) / scale
            }
        }
        context.registerReceiver(receiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return percent
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
