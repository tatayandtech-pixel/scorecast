package com.scorecast.app

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.scorecast.app.theme.ScoreCastStatusOk
import com.scorecast.app.theme.ScoreCastTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SportConfigLoader.loadAll(this)
        GameStateHolder.update { copy(scoreboardLight = ScoreboardStylePrefs.isLight(this@MainActivity)) }
        setContent {
            ScoreCastTheme {
                AppRoot()
            }
        }
    }
}

/** Navigation skeleton (spec Appendix B/A): portrait setup screens, landscape in-game screen. */
private enum class Screen {
    HOME, SETTINGS, MATCHES, WIZARD_SPORT, WIZARD_TEAMS, WIZARD_PLATFORM, WIZARD_DESTINATION, IN_GAME,
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
            // Mirror scoring device: free to rotate (owner request) — a wider landscape layout is
            // easier to score from than the portrait-only lock every other setup screen uses.
            Screen.MIRROR_SCORING -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onCreateStream = { screen = Screen.MATCHES },
            onJoinAsRemote = { screen = Screen.MIRROR_SCAN },
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen(
            onBack = { screen = Screen.HOME },
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
    val coroutineScope = rememberCoroutineScope()
    val streamerState by StreamerHolder.state.collectAsState()
    val flip        by StreamerHolder.flip.collectAsState()
    val zoom        by StreamerHolder.zoomRatio.collectAsState()

    var ingestUrl     by rememberSaveable { mutableStateOf(initialTarget.ingestUrl) }
    var streamKey     by rememberSaveable { mutableStateOf(initialTarget.streamKey) }
    var recordingMode by rememberSaveable { mutableStateOf(initialTarget.mode) }
    var fullscreenVideo by rememberSaveable { mutableStateOf(false) }
    var startRequested by remember { mutableStateOf(false) }
    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }

    val isLive = streamerState is StreamerHolder.State.Live || streamerState is StreamerHolder.State.Starting
    // A dead stream must not yank the operator back to the setup panel mid-match — the game is
    // still being played and they're still scoring. Keep the live UI mounted for Error too and
    // surface the failure in place (LiveErrorBanner); only Idle returns to setup.
    val showLiveUi = isLive || streamerState is StreamerHolder.State.Error

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

    // Live mode reserves the top 1/4 of the screen for video so the permanent controls area below
    // has real room; the fullscreen toggle temporarily restores fillMaxSize. Either way this is
    // just the AndroidView's Modifier changing — the SurfaceView itself is never recreated (see
    // the factory comment below), so this can't reintroduce the camera-session-recreation bug.
    val videoConstrained = showLiveUi && !fullscreenVideo

    Box(Modifier.fillMaxSize()) {
        // Camera always fills the screen so the SurfaceView is never recreated.
        // A float[] tag lets the pinch handler read the current zoom without
        // re-running the factory lambda.
        AndroidView(
            modifier = if (videoConstrained) {
                Modifier.fillMaxHeight(0.38f).aspectRatio(StreamConfig.RESOLUTION.width / StreamConfig.RESOLUTION.height.toFloat()).align(Alignment.TopStart)
            } else {
                Modifier.fillMaxSize()
            },
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    surfaceViewRef = this
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

        if (!showLiveUi) {
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
            // ── LIVE mode: 1/4-height video strip + a permanent controls area ──
            LiveOverlay(
                fullscreenVideo    = fullscreenVideo,
                onToggleFullscreen = { fullscreenVideo = !fullscreenVideo },
                streamerState      = streamerState,
                onRetryStream      = onGoLive,
                onDismissError     = { StreamerHolder.acknowledgeError() },
                onCapturePhoto     = {
                    // The preview SurfaceView is rendered at whatever size it's displayed at —
                    // in the compact 1/4-height layout that's a low-res sliver, not a real photo.
                    // Flash to fullscreen just long enough for the surface to resize up, capture,
                    // then restore whatever mode the operator was actually in.
                    val wasFullscreen = fullscreenVideo
                    fullscreenVideo = true
                    coroutineScope.launch {
                        delay(350)
                        surfaceViewRef?.let { sv -> capturePhoto(context, sv) }
                        fullscreenVideo = wasFullscreen
                    }
                },
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
    fullscreenVideo: Boolean,
    onToggleFullscreen: () -> Unit,
    streamerState: StreamerHolder.State,
    onRetryStream: () -> Unit,
    onDismissError: () -> Unit,
    onCapturePhoto: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    var showPairDialog by remember { mutableStateOf(false) }
    var showEndMatchConfirm by remember { mutableStateOf(false) }
    var showMenuStub by remember { mutableStateOf(false) }
    var micMuted by remember { mutableStateOf(false) }
    val isRecording = remember(streamerState) { StreamerHolder.currentRecordingFile != null }
    val batteryPct = rememberBatteryPercent()

    if (fullscreenVideo) {
        // Fullscreen mode, laid out to match the owner's reference photo: video-status chips
        // bottom-left, exit-fullscreen top-end, a compact light/dark toggle for the burned-in
        // scoreboard sitting in the gap to the right of the bar itself (not a Compose element —
        // the bar is baked into the video frame — so this is an empirical offset matched to
        // where the bar renders on this device, not a computed alignment), a vertical zoom slider
        // + step buttons + battery readout down the right edge, and stacked photo/mic circular
        // buttons bottom-end. The permanent scrollable controls area is still hidden here — this
        // is a lean, glance-and-tap surface for framing/operating the shot itself, per the reference.
        val gameState by GameStateHolder.state.collectAsState()
        Box(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Error keeps the live UI mounted now, so this chip can no longer assume
                // "not starting" means "on air" — it would have read "● LIVE" over a dead stream.
                // Live/Starting keep the pure-red/yellow tally-light convention (DESIGN.md);
                // off-air is not a tally state, so it uses the theme's own colours.
                val (liveChipText, liveChipColor) = when (streamerState) {
                    StreamerHolder.State.Live -> "● LIVE" to Color.Red
                    StreamerHolder.State.Starting -> "● Starting…" to Color.Yellow
                    is StreamerHolder.State.Error -> "○ Off air" to MaterialTheme.colorScheme.error
                    StreamerHolder.State.Idle -> "○ Off air" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Chip(text = liveChipText, color = liveChipColor)
                Chip(
                    text = if (isRecording) "● Recording" else "○ Not being stored",
                    color = if (isRecording) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TopBarIconButton(
                "⤢",
                contentDescription = "Exit fullscreen",
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                onClick = onToggleFullscreen,
            )
            FullscreenCircleButton(
                if (gameState.scoreboardLight) "☀" else "🌙",
                contentDescription = if (gameState.scoreboardLight) "Switch scoreboard to dark background" else "Switch scoreboard to light background",
                size = 44.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp, end = 100.dp),
            ) {
                val newLight = GameStateHolder.toggleScoreboardBackground()
                ScoreboardStylePrefs.setLight(context, newLight)
            }
            // Single column, top-anchored below the restore-fullscreen button (not vertically
            // centered) — this device's landscape height (~360dp) is too short to center a taller
            // stack without it overflowing both top and bottom edges, which is what collided with
            // the restore button before this fix.
            FullscreenSideControls(
                batteryPct = batteryPct,
                micMuted = micMuted,
                onToggleMic = {
                    micMuted = !micMuted
                    (context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager)
                        ?.isMicrophoneMute = micMuted
                },
                onCapturePhoto = onCapturePhoto,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 12.dp),
            )
        }
    } else {
        // Video aspect ratio must match the AndroidView sibling in AppRoot's Box so this row's
        // left-hand spacer lines up exactly with the actual rendered video underneath.
        val videoAspectRatio = StreamConfig.RESOLUTION.width / StreamConfig.RESOLUTION.height.toFloat()

        // Owner-requested reference-match redesign (light background, yellow accent) — scoped to
        // this screen only via LiveScoringView.kt's composables; see that file's header comment.
        val state by GameStateHolder.state.collectAsState()
        val config = remember(state.sport) {
            SportConfigLoader.getCached(state.sport)
                ?: SportConfig(sport = "basketball", displayName = "Basketball", periods = 4,
                    periodLabel = "Q", periodLength = 600, clockDirection = "down", scoreIncrements = listOf(1, 2, 3))
        }
        var tickMs by remember { mutableLongStateOf(ServerTimeSync.nowMs()) }
        LaunchedEffect(state.clockRunning, state.shotClockRunning) {
            if (state.clockRunning || state.shotClockRunning) while (true) { delay(500); tickMs = ServerTimeSync.nowMs() }
        }
        val displaySeconds = state.clockDisplay(tickMs)
        val shotClockSeconds = state.shotClockDisplay(tickMs)
        val resetSeconds = if (state.clockDirection == "up") 0f else config.periodLength.toFloat()
        val isSetsGames = config.scoringModel == "setsGames"
        var showClockEditDialog by remember { mutableStateOf(false) }

        // No background on this root Column — the video Box below is a transparent placeholder
        // (the real camera feed is the AndroidView sibling drawn underneath in AppRoot's Box);
        // an opaque background here would paint over it. The light theme is applied only to the
        // two sub-sections that don't overlap the video: the status column and the bottom panel.
        Column(Modifier.fillMaxSize()) {
            // Top strip, sized to match the video's fillMaxHeight(0.38f) in AppRoot: video pinned
            // top-left (this Box is a transparent spacer — the real video renders underneath via
            // the AndroidView sibling), status/menu bar fills the rest of the strip to its right.
            // Bumped from 0.32f to make room for the REMOTE SCORING row's now-accessible (44dp+)
            // Switch and icon targets — verify on-device if adjusting this again, this strip's
            // height has previously collided with the Countdown/Shot Clock row below it.
            Row(Modifier.fillMaxWidth().fillMaxHeight(0.38f)) {
                Box(Modifier.fillMaxHeight().aspectRatio(videoAspectRatio)) {
                    // Owner request: the small non-fullscreen preview no longer overlays the
                    // LIVE/Recording status chips on the video itself — that status is still
                    // shown in fullscreen mode (LiveOverlay's other branch), just not duplicated
                    // here on the compact preview. The "⤢" expand button used to live here too,
                    // but this Box sits exactly on top of the real embedded camera SurfaceView
                    // (same bounds), which has its own touch listener for pinch-zoom and was
                    // swallowing the tap before Compose's button ever saw it — it's now inline
                    // in the REMOTE SCORING row's icon cluster instead (see below).
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(LiveTheme.Background)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val shareAction = {
                        context.startActivity(
                            android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "Live now on ScoreCast: ${state.homeTeam} vs ${state.awayTeam}",
                                    )
                                },
                                "Share",
                            )
                        )
                    }
                    // Remote-scoring toggle + scorer-connection dot (spec §9), backed by Firebase
                    // presence — combined onto one row (was two) to free vertical room below for
                    // the Countdown/Shot Clock row, which now fills the wide empty space this
                    // column used to leave unused next to the video.
                    val session by SessionHolder.session.collectAsState()
                    val mirrorConnected by FirebaseSessionSync.mirrorConnected.collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("REMOTE SCORING", color = LiveTheme.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        // No explicit height here — Switch's own default touch target (~48dp,
                        // via minimumInteractiveComponentSize) was previously being overridden
                        // down to 20dp, shrinking the real tap target well below the 44dp floor.
                        Switch(checked = session != null, onCheckedChange = { showPairDialog = true })
                        Text(
                            if (mirrorConnected) "● Referee online" else "● Referee offline",
                            color = if (mirrorConnected) ScoreCastStatusOk() else LiveTheme.TextMuted,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        // Each icon gets a 44dp hit box (PRODUCT.md's touch-target floor) around
                        // its small glyph, rather than the glyph's own bare text bounds — these
                        // four sit close together, so an undersized target on any one of them
                        // raises the odds of hitting a neighbor instead.
                        Box(
                            modifier = Modifier.size(44.dp).clickable {
                                micMuted = !micMuted
                                (context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager)
                                    ?.isMicrophoneMute = micMuted
                            }.semantics { contentDescription = if (micMuted) "Unmute microphone" else "Mute microphone" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (micMuted) "🔇" else "🎤", fontSize = 16.sp)
                        }
                        Box(
                            modifier = Modifier.size(44.dp).clickable(onClick = shareAction)
                                .semantics { contentDescription = "Share" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🔗", fontSize = 16.sp)
                        }
                        Box(
                            modifier = Modifier.size(44.dp).clickable { showMenuStub = true }
                                .semantics { contentDescription = "More options" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("⋮", color = LiveTheme.TextPrimary, fontSize = 16.sp)
                        }
                        // In-line with the other icons in this row rather than floated as its
                        // own overlay box — a floated box here collided with this row's own
                        // icons (same top-right corner), so a tap meant for "⤢" could land on
                        // "⋮" or the link icon instead depending on exact finger position.
                        Box(
                            modifier = Modifier.size(44.dp).clickable(onClick = onToggleFullscreen)
                                .semantics { contentDescription = "Enter fullscreen" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("⤢", color = LiveTheme.TextPrimary, fontSize = 16.sp)
                        }
                    }

                    // Countdown is meaningless for clockDirection "none" sports (volleyball,
                    // badminton, squash, table tennis) — clockDisplay() returns 0f for them, so
                    // without this guard the operator got a permanent "COUNTDOWN 0:00" with a live
                    // play button and adjusters that could never do anything. ScoringPanel.kt
                    // already guarded this the same way; the live view never did.
                    val showCountdown = state.clockDirection != "none"
                    val shotClockConfigSeconds = config.shotClockSeconds
                    if (showCountdown || shotClockConfigSeconds != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            if (showCountdown) {
                                LiveCountdownSection(
                                    displaySeconds = displaySeconds,
                                    clockRunning = state.clockRunning,
                                    onToggleRunning = {
                                        if (state.clockRunning) GameStateHolder.stopClock() else GameStateHolder.startClock()
                                    },
                                    onEdit = { showClockEditDialog = true },
                                    onAdjust = { GameStateHolder.adjustClock(it) },
                                    onReset = { GameStateHolder.resetClock(resetSeconds) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Shot clock — basketball only (config.shotClockSeconds) — now lives beside
                            // the Countdown instead of stacked below the team cards, since this row has
                            // plenty of width to spare.
                            if (shotClockConfigSeconds != null) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("SHOT CLOCK", color = LiveTheme.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(shotClockSeconds.toClockString(), color = LiveTheme.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        LiveSmallButton(if (state.shotClockRunning) "Stop" else "Start") {
                                            if (state.shotClockRunning) GameStateHolder.stopShotClock() else GameStateHolder.startShotClock()
                                        }
                                        LiveSmallButton("-5") { GameStateHolder.adjustShotClock(-5f) }
                                        LiveSmallButton("+5") { GameStateHolder.adjustShotClock(5f) }
                                        LiveSmallButton("Rst") { GameStateHolder.resetShotClock(shotClockConfigSeconds.toFloat()) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Rest of the screen: team cards + bottom bar, sized to fill the remaining height
            // exactly (no scrolling — Countdown/Shot Clock moved up into the video strip's empty
            // space specifically to make this fit in one screen).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(LiveTheme.Background)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                (streamerState as? StreamerHolder.State.Error)?.let { errorState ->
                    LiveErrorBanner(
                        message = errorState.message,
                        onRetry = onRetryStream,
                        onDismiss = onDismissError,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LiveTeamCard(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        teamName = state.homeTeam,
                        score = state.homeScore,
                        setsWon = if (isSetsGames) state.setsWonHome else null,
                        colorHex = state.homeColorHex,
                        scoreIncrements = config.scoreIncrements,
                        onNameChange = { GameStateHolder.update { copy(homeTeam = it) } },
                        onScoreChange = {
                            if (isSetsGames) GameStateHolder.addSetsGamesScore(true, it, config)
                            else GameStateHolder.update { copy(homeScore = (homeScore + it).coerceAtLeast(0)) }
                        },
                        onColorChange = { GameStateHolder.update { copy(homeColorHex = it) } },
                    )
                    LiveTeamCard(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        teamName = state.awayTeam,
                        score = state.awayScore,
                        setsWon = if (isSetsGames) state.setsWonAway else null,
                        colorHex = state.awayColorHex,
                        scoreIncrements = config.scoreIncrements,
                        onNameChange = { GameStateHolder.update { copy(awayTeam = it) } },
                        onScoreChange = {
                            if (isSetsGames) GameStateHolder.addSetsGamesScore(false, it, config)
                            else GameStateHolder.update { copy(awayScore = (awayScore + it).coerceAtLeast(0)) }
                        },
                        onColorChange = { GameStateHolder.update { copy(awayColorHex = it) } },
                    )
                }

                LiveBottomBar(
                    onStop = { showEndMatchConfirm = true },
                    streamerState = streamerState,
                    isRecording = isRecording,
                    periodLabel = state.periodLabel,
                    period = state.period,
                    maxPeriod = config.periods,
                    onPeriodMinus = { GameStateHolder.update { copy(period = (period - 1).coerceAtLeast(1)) } },
                    onPeriodPlus = { if (state.period < config.periods) GameStateHolder.update { copy(period = period + 1) } },
                    batteryPct = batteryPct,
                    onShare = {
                        context.startActivity(
                            android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "Live now on ScoreCast: ${state.homeTeam} vs ${state.awayTeam}",
                                    )
                                },
                                "Share",
                            )
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        if (showClockEditDialog) {
            ClockEditDialog(
                initialSeconds = displaySeconds,
                onDismiss = { showClockEditDialog = false },
                onConfirm = { totalSeconds ->
                    GameStateHolder.resetClock(totalSeconds)
                    showClockEditDialog = false
                },
            )
        }
    }

    if (showPairDialog) {
        val session = remember { SessionHolder.ensureSession() }
        val qrBitmap = remember(session) { QrCodeUtil.generate(session.toQrPayload()).asImageBitmap() }
        val clipboard = LocalClipboardManager.current
        val pairingCode = "${session.sessionId}:${session.joinToken}"
        // Copies the link alongside the code (not just the bare code) so the whole thing can be
        // pasted straight into a text/chat message to the remote scorer, who needs both pieces.
        val shareText = "Join my ScoreCast match: https://scorecast-app-625c0.web.app  Code: $pairingCode"
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
                            "Android: scan this code. iPhone/laptop: go to scorecast-app-625c0.web.app and enter the code below.",
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
                            clipboard.setText(AnnotatedString(shareText))
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
                // Destructive confirm renders in the error role, not the default accent: as plain
                // TextButtons this and Cancel were the same Signal Lavender at the same weight, so
                // under time pressure the only differentiator was left-vs-right position.
                // DESIGN.md's Cross-Platform Mapping already assigns "End match" to the error role.
                TextButton(
                    onClick = { showEndMatchConfirm = false; onStop() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("End match") }
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
private fun TopBarIconButton(
    label: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Fixed-size Box instead of sizing off the glyph's own intrinsic bounds — some glyphs (e.g.
    // "⋮") measure far narrower than they look, which was shrinking the real tap target down to
    // a sliver despite the visible background looking full-size.
    Box(
        modifier = modifier
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FullscreenCircleButton(
    label: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Small radiating "aperture" glyph decorating the top of the fullscreen zoom track (reference
 *  photo) — purely decorative, no state of its own. */
@Composable
private fun ApertureIcon(size: androidx.compose.ui.unit.Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        for (i in 0 until 6) {
            rotate(i * 60f, pivot = Offset(cx, cy)) {
                drawLine(
                    color = color,
                    start = Offset(cx, cy - r * 0.35f),
                    end = Offset(cx, cy - r * 0.95f),
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round,
                )
            }
        }
        drawCircle(color = color, radius = r * 0.18f, center = Offset(cx, cy))
    }
}

/** Custom vertical zoom slider for fullscreen mode (reference photo): a dotted track the operator
 *  drags directly, rather than reusing the horizontal Material [Slider] rotated — a rotated
 *  Material Slider fights its own touch-target math, while this reads/writes the drag position
 *  directly against [valueRange]. Top of the track = max zoom, bottom = min zoom. */
@Composable
private fun VerticalDottedZoomSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    var trackHeightPx by remember { mutableFloatStateOf(1f) }
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val thumbFraction = 1f - fraction

    Box(
        modifier = modifier
            .onSizeChanged { trackHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(valueRange) {
                detectVerticalDragGestures { change, _ ->
                    val y = change.position.y.coerceIn(0f, trackHeightPx)
                    val f = 1f - (y / trackHeightPx)
                    onValueChange(valueRange.start + f * span)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f), 0f)
            drawLine(
                color = Color.White.copy(alpha = 0.5f),
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = 3f,
                pathEffect = dashEffect,
            )
            val thumbRadius = size.width / 2.2f
            val thumbY = (thumbFraction * size.height).coerceIn(thumbRadius, size.height - thumbRadius)
            drawCircle(color = Color.White, radius = thumbRadius, center = Offset(size.width / 2f, thumbY))
        }
    }
}

/** Right-edge stack for fullscreen mode (reference photo): aperture glyph, draggable zoom track,
 *  step +/- buttons, battery readout, and photo/mic buttons — everything the operator needs
 *  without leaving fullscreen, in one column sized to fit even a short landscape screen (this
 *  device's ~360dp usable height doesn't leave room for two independently-positioned stacks
 *  without them overlapping — see the call site's comment). */
@Composable
private fun FullscreenSideControls(
    batteryPct: Int,
    micMuted: Boolean,
    onToggleMic: () -> Unit,
    onCapturePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoom by StreamerHolder.zoomRatio.collectAsState()
    var range by remember { mutableStateOf(1f..5f) }
    LaunchedEffect(Unit) { range = StreamerHolder.getZoomRange() }
    val step = (range.endInclusive - range.start) / 20f

    Column(
        modifier = modifier.width(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ApertureIcon(size = 16.dp, color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(2.dp))
        VerticalDottedZoomSlider(
            value = zoom.coerceIn(range.start, range.endInclusive),
            onValueChange = { StreamerHolder.setZoomRatio(it.coerceIn(range.start, range.endInclusive)) },
            valueRange = range,
            modifier = Modifier
                .width(20.dp)
                .height(90.dp),
        )
        Spacer(Modifier.height(2.dp))
        // 44dp (PRODUCT.md's touch-target floor) — was 28dp; this column's height budget is tight
        // on short landscape screens (see the class doc above), so surrounding spacers were trimmed
        // to compensate. Verify on-device after any further change here.
        FullscreenCircleButton("▲", contentDescription = "Zoom in", size = 44.dp) {
            StreamerHolder.setZoomRatio((zoom + step).coerceAtMost(range.endInclusive))
        }
        Spacer(Modifier.height(0.dp))
        FullscreenCircleButton("▼", contentDescription = "Zoom out", size = 44.dp) {
            StreamerHolder.setZoomRatio((zoom - step).coerceAtLeast(range.start))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("🔋", style = MaterialTheme.typography.labelSmall, color = Color.White)
            Text("$batteryPct%", style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        FullscreenCircleButton("📷", contentDescription = "Take photo", size = 44.dp, onClick = onCapturePhoto)
        Spacer(Modifier.height(4.dp))
        FullscreenCircleButton(
            if (micMuted) "🔇" else "🎤",
            contentDescription = if (micMuted) "Unmute microphone" else "Mute microphone",
            size = 44.dp,
            onClick = onToggleMic,
        )
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
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) { Text("−", fontSize = 14.sp) }
        Text("%.1fx".format(zoom), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(40.dp))
        OutlinedButton(
            onClick = { StreamerHolder.setZoomRatio(zoom + 0.5f) },
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) { Text("+", fontSize = 14.sp) }
        OutlinedButton(
            onClick = { StreamerHolder.setZoomRatio(1f) },
            modifier = Modifier.height(44.dp),
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
                modifier = Modifier.height(44.dp),
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

/**
 * Grabs the currently-displayed preview frame (camera + burned-in scoreboard + logos, already
 * composited — see [com.scorecast.app.overlay.OverlayCompositor]) via [PixelCopy] and saves it as
 * a JPEG. Reads from the display surface, not the encoder path, so this can't destabilize the
 * StreamPack/Camera2 streaming pipeline the way touching [StreamerHolder] directly could.
 */
private fun capturePhoto(context: android.content.Context, surfaceView: SurfaceView) {
    if (surfaceView.width == 0 || surfaceView.height == 0) return
    val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
    val handlerThread = HandlerThread("PhotoCapture").apply { start() }
    val mainHandler = Handler(context.mainLooper)
    PixelCopy.request(surfaceView, bitmap, { result ->
        handlerThread.quitSafely()
        if (result == PixelCopy.SUCCESS) {
            RecordingsManager.savePhoto(context, bitmap)
            mainHandler.post { Toast.makeText(context, "Photo saved", Toast.LENGTH_SHORT).show() }
        } else {
            mainHandler.post { Toast.makeText(context, "Couldn't capture photo", Toast.LENGTH_SHORT).show() }
        }
    }, Handler(handlerThread.looper))
}
