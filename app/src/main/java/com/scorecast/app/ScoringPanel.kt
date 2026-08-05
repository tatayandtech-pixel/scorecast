package com.scorecast.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal val PRESET_COLORS = listOf(
    "#1E40AF", "#B91C1C", "#15803D", "#7C3AED",
    "#0F766E", "#B45309", "#374151", "#6B7280",
)

private val FALLBACK_CONFIG = SportConfig(
    sport = "basketball",
    displayName = "Basketball",
    periods = 4,
    periodLabel = "Q",
    periodLength = 600,
    clockDirection = "down",
    scoreIncrements = listOf(1, 2, 3),
)

@Composable
fun ScoringPanel() {
    val state by GameStateHolder.state.collectAsState()

    val config = remember(state.sport) {
        SportConfigLoader.getCached(state.sport) ?: FALLBACK_CONFIG
    }

    var tickMs by remember { mutableLongStateOf(ServerTimeSync.nowMs()) }
    LaunchedEffect(state.clockRunning, state.shotClockRunning) {
        if (state.clockRunning || state.shotClockRunning) while (true) { delay(500); tickMs = ServerTimeSync.nowMs() }
    }
    val displaySeconds = state.clockDisplay(tickMs)
    val shotClockSeconds = state.shotClockDisplay(tickMs)
    val resetSeconds = if (state.clockDirection == "up") 0f else config.periodLength.toFloat()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Scoring", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp))

        // Team columns.
        val isSetsGames = config.scoringModel == "setsGames"
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeamColumn(
                modifier = Modifier.weight(1f),
                label = "Home",
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
            TeamColumn(
                modifier = Modifier.weight(1f),
                label = "Away",
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

        Spacer(Modifier.height(6.dp))

        // Period row.
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Period", style = MaterialTheme.typography.bodySmall)
            SmallButton("-", contentDescription = "Previous period") {
                GameStateHolder.update { copy(period = (period - 1).coerceAtLeast(1)) }
            }
            Text("${state.periodLabel}${state.period}",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
            SmallButton("+", contentDescription = "Next period") {
                if (state.period < config.periods)
                    GameStateHolder.update { copy(period = period + 1) }
            }
            Text("/ ${config.periods}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(6.dp))

        // Clock row — hidden for sports with no clock (e.g. volleyball).
        if (state.clockDirection != "none") {
            var showEditDialog by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Clock", style = MaterialTheme.typography.bodySmall)
                Text(displaySeconds.toClockString(),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(52.dp), textAlign = TextAlign.Center)
                SmallButton("✎", contentDescription = "Edit clock") { showEditDialog = true }
                SmallButton(if (state.clockRunning) "Stop" else "Start") {
                    if (state.clockRunning) GameStateHolder.stopClock()
                    else GameStateHolder.startClock()
                }
                SmallButton("-30") { GameStateHolder.adjustClock(-30f) }
                SmallButton("+30") { GameStateHolder.adjustClock(30f) }
                SmallButton("Rst") { GameStateHolder.resetClock(resetSeconds) }
            }
            Spacer(Modifier.height(6.dp))

            if (showEditDialog) {
                ClockEditDialog(
                    initialSeconds = displaySeconds,
                    onDismiss = { showEditDialog = false },
                    onConfirm = { totalSeconds ->
                        GameStateHolder.resetClock(totalSeconds)
                        showEditDialog = false
                    },
                )
            }
        }

        // Shot clock row — basketball only (config.shotClockSeconds), hidden for every other sport.
        if (config.shotClockSeconds != null) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Shot Clock", style = MaterialTheme.typography.bodySmall)
                Text(shotClockSeconds.toClockString(),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(52.dp), textAlign = TextAlign.Center)
                SmallButton(if (state.shotClockRunning) "Stop" else "Start") {
                    if (state.shotClockRunning) GameStateHolder.stopShotClock()
                    else GameStateHolder.startShotClock()
                }
                SmallButton("-5") { GameStateHolder.adjustShotClock(-5f) }
                SmallButton("+5") { GameStateHolder.adjustShotClock(5f) }
                SmallButton("Rst") { GameStateHolder.resetShotClock(config.shotClockSeconds.toFloat()) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun TeamColumn(
    modifier: Modifier = Modifier,
    label: String,
    teamName: String,
    score: Int,
    setsWon: Int? = null,
    colorHex: String,
    scoreIncrements: List<Int>,
    onNameChange: (String) -> Unit,
    onScoreChange: (Int) -> Unit,
    onColorChange: (String) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            if (setsWon != null) {
                Text("· Sets won: $setsWon", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Buffers keystrokes locally and only pushes to GameStateHolder (and thus Firebase) on
        // focus loss, instead of syncing on every keystroke. Per-keystroke sync raced against the
        // sync engine's own echo of each write (which replaces the whole local GameState — see
        // FirebaseSessionSync.attachListener) and could clobber an in-progress edit with a stale,
        // partially-typed value — reproduced live: a team name landed stuck at "AW" instead of the
        // fully-typed name. Committing once on blur removes the window for that race entirely, and
        // is arguably better UX anyway (viewers don't need to see a name update mid-keystroke).
        var isFocused by remember { mutableStateOf(false) }
        var localName by remember { mutableStateOf(teamName) }
        LaunchedEffect(teamName) {
            if (!isFocused) localName = teamName
        }
        OutlinedTextField(
            value = localName,
            onValueChange = { localName = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (isFocused && !focusState.isFocused) onNameChange(localName)
                    isFocused = focusState.isFocused
                },
        )
        // Score stepper: always a -1, then the score, then dynamic +N buttons.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            SmallButton("-1") { onScoreChange(-1) }
            Text(score.toString(),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
            scoreIncrements.forEach { inc ->
                SmallButton("+$inc") { onScoreChange(inc) }
            }
        }
        // Color presets. 44dp tap target (PRODUCT.md's touch-target floor) wrapping a smaller
        // 18dp visual dot, plus horizontalScroll, since 8 full-size 44dp swatches side by side
        // would overflow this panel's width.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            PRESET_COLORS.forEach { hex ->
                val argb = try {
                    android.graphics.Color.parseColor(hex)
                } catch (_: Exception) { android.graphics.Color.GRAY }
                val selected = colorHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { onColorChange(hex) }
                        .semantics {
                            contentDescription = "$label color $hex"
                            this.selected = selected
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
                    )
                }
            }
        }
    }
}

/** Spec Appendix A "pencil = manual edit" affordance on the clock. */
@Composable
internal fun ClockEditDialog(
    initialSeconds: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    val initialTotal = initialSeconds.toInt()
    var minutes by remember { mutableStateOf((initialTotal / 60).toString()) }
    var seconds by remember { mutableStateOf((initialTotal % 60).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit clock") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { if (it.all(Char::isDigit) && it.length <= 3) minutes = it },
                    label = { Text("min") },
                    singleLine = true,
                    modifier = Modifier.width(80.dp),
                )
                Text(":", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { if (it.all(Char::isDigit) && it.length <= 2) seconds = it },
                    label = { Text("sec") },
                    singleLine = true,
                    modifier = Modifier.width(80.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val total = (minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0).coerceIn(0, 59)
                onConfirm(total.toFloat())
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun SmallButton(label: String, contentDescription: String? = null, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .height(44.dp)
            .then(
                if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
                else Modifier
            ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(label, fontSize = 11.sp)
    }
}
