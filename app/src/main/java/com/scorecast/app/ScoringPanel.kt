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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal val PRESET_COLORS = listOf(
    "#1E40AF", "#B91C1C", "#15803D", "#7C3AED",
    "#0F766E", "#B45309", "#374151", "#6B7280",
)

private val POSITION_LABELS = mapOf(
    OverlayPosition.TOP_LEFT to "TL",
    OverlayPosition.TOP_RIGHT to "TR",
    OverlayPosition.BOTTOM_LEFT to "BL",
    OverlayPosition.BOTTOM_CENTER to "BC",
    OverlayPosition.BOTTOM_RIGHT to "BR",
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
    val context = LocalContext.current
    val state by GameStateHolder.state.collectAsState()

    val allConfigs = remember { SportConfigLoader.loadAll(context) }
    val config = remember(state.sport) {
        SportConfigLoader.getCached(state.sport) ?: FALLBACK_CONFIG
    }

    var tickMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.clockRunning) {
        if (state.clockRunning) while (true) { delay(500); tickMs = System.currentTimeMillis() }
    }
    val displaySeconds = when (state.clockDirection) {
        "up" -> if (state.clockRunning) {
            val elapsed = (tickMs - state.startedAtMs) / 1000f
            state.baseRemainingSeconds + elapsed
        } else state.baseRemainingSeconds
        "none" -> 0f
        else -> if (state.clockRunning) {
            val elapsed = (tickMs - state.startedAtMs) / 1000f
            (state.baseRemainingSeconds - elapsed).coerceAtLeast(0f)
        } else state.baseRemainingSeconds
    }
    val resetSeconds = if (state.clockDirection == "up") 0f else config.periodLength.toFloat()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Scoring", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp))

        // Sport picker.
        SportPickerRow(allConfigs, state.sport)

        Spacer(Modifier.height(6.dp))

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

        // Extra fields (fouls, cards, etc.).
        ExtraFieldsSection(state, config)

        // Player roster (only for sports that use individual player tracking).
        PlayersSection(state)

        Spacer(Modifier.height(6.dp))

        // Period row.
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Period", style = MaterialTheme.typography.bodySmall)
            SmallButton("-") {
                GameStateHolder.update { copy(period = (period - 1).coerceAtLeast(1)) }
            }
            Text("${state.periodLabel}${state.period}",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
            SmallButton("+") {
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
                SmallButton("✎") { showEditDialog = true }
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

        // Overlay position row.
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Position", style = MaterialTheme.typography.bodySmall)
            OverlayPosition.entries.forEach { pos ->
                val selected = state.overlayPosition == pos
                OutlinedButton(
                    onClick = { GameStateHolder.update { copy(overlayPosition = pos) } },
                    modifier = Modifier.height(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    colors = if (selected) ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) else ButtonDefaults.outlinedButtonColors(),
                ) {
                    Text(POSITION_LABELS[pos] ?: "", fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Custom text banner.
        OutlinedTextField(
            value = state.customText,
            onValueChange = { GameStateHolder.update { copy(customText = it) } },
            label = { Text("Banner text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SportPickerRow(allConfigs: List<SportConfig>, currentSport: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Sport", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            allConfigs.forEach { cfg ->
                val selected = cfg.sport == currentSport
                OutlinedButton(
                    onClick = { GameStateHolder.selectSport(cfg) },
                    modifier = Modifier.height(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = if (selected) ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) else ButtonDefaults.outlinedButtonColors(),
                ) { Text(cfg.displayName, fontSize = 11.sp) }
            }
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
        OutlinedTextField(
            value = teamName,
            onValueChange = onNameChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
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
        // Color presets.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PRESET_COLORS.forEach { hex ->
                val argb = try {
                    android.graphics.Color.parseColor(hex)
                } catch (_: Exception) { android.graphics.Color.GRAY }
                val selected = colorHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                        .clickable { onColorChange(hex) }
                )
            }
        }
    }
}

@Composable
private fun ExtraFieldsSection(state: GameState, config: SportConfig) {
    if (config.extraFields.isEmpty()) return

    Spacer(Modifier.height(4.dp))
    Text("Stats", style = MaterialTheme.typography.bodySmall)

    config.extraFields.filter { it.perTeam }.forEach { field ->
        val homeVal = state.extraFields["${field.key}_home"] ?: 0
        val awayVal = state.extraFields["${field.key}_away"] ?: 0
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(field.label, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(64.dp))
            Text("H:", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp))
            SmallButton("-") {
                GameStateHolder.setExtraFieldTeam(field.key, true, (homeVal - 1).coerceAtLeast(0))
            }
            Text(homeVal.toString(), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(22.dp), textAlign = TextAlign.Center)
            SmallButton("+") {
                val v = if (field.max != null) (homeVal + 1).coerceAtMost(field.max) else homeVal + 1
                GameStateHolder.setExtraFieldTeam(field.key, true, v)
            }
            Spacer(Modifier.width(6.dp))
            Text("A:", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp))
            SmallButton("-") {
                GameStateHolder.setExtraFieldTeam(field.key, false, (awayVal - 1).coerceAtLeast(0))
            }
            Text(awayVal.toString(), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(22.dp), textAlign = TextAlign.Center)
            SmallButton("+") {
                val v = if (field.max != null) (awayVal + 1).coerceAtMost(field.max) else awayVal + 1
                GameStateHolder.setExtraFieldTeam(field.key, false, v)
            }
        }
    }

    config.extraFields.filter { !it.perTeam }.forEach { field ->
        val v = state.extraFields[field.key] ?: 0
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(field.label, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(64.dp))
            SmallButton("-") { GameStateHolder.setExtraField(field.key, (v - 1).coerceAtLeast(0)) }
            Text(v.toString(), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(22.dp), textAlign = TextAlign.Center)
            SmallButton("+") {
                val nv = if (field.max != null) (v + 1).coerceAtMost(field.max) else v + 1
                GameStateHolder.setExtraField(field.key, nv)
            }
        }
    }
}

@Composable
private fun PlayersSection(state: GameState) {
    if (state.playersHome.isEmpty() && state.playersAway.isEmpty()) return

    Spacer(Modifier.height(4.dp))
    Text("Players", style = MaterialTheme.typography.bodySmall)

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerRoster(
            modifier = Modifier.weight(1f),
            label = "Home",
            players = state.playersHome,
            onNameChange = { i, name -> GameStateHolder.setPlayerHome(i, name) },
            onAdd = { GameStateHolder.addPlayerHome() },
            onRemove = { i -> GameStateHolder.removePlayerHome(i) },
        )
        PlayerRoster(
            modifier = Modifier.weight(1f),
            label = "Away",
            players = state.playersAway,
            onNameChange = { i, name -> GameStateHolder.setPlayerAway(i, name) },
            onAdd = { GameStateHolder.addPlayerAway() },
            onRemove = { i -> GameStateHolder.removePlayerAway(i) },
        )
    }
}

@Composable
private fun PlayerRoster(
    modifier: Modifier = Modifier,
    label: String,
    players: List<String>,
    onNameChange: (Int, String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        players.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { onNameChange(i, it) },
                    label = { Text("P${i + 1}", fontSize = 9.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                SmallButton("×") { onRemove(i) }
            }
        }
        SmallButton("+ Player") { onAdd() }
    }
}

/** Spec Appendix A "pencil = manual edit" affordance on the clock. */
@Composable
private fun ClockEditDialog(
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
internal fun SmallButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(28.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(label, fontSize = 11.sp)
    }
}
