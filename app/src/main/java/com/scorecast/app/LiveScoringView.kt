package com.scorecast.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Owner-requested reference-match redesign of the live (non-fullscreen) in-game screen — a
 * light/yellow visual language distinct from the rest of ScoreCast's dark "Sideline Console"
 * brand (DESIGN.md), scoped ONLY to this screen. Deliberately NOT applied to the shared
 * [ScoringPanel] (still used, unstyled, by the wizard's Scoring-tab preview and
 * [MirrorScoringScreen]) — this file's composables are new and independent so those two surfaces
 * are untouched.
 */
/**
 * Shown in place when the stream dies mid-match. The live UI deliberately stays up behind it: the
 * game is still being played and the operator is still scoring, so tearing the whole screen back
 * to the pre-live setup panel (which is what happened before, silently, with the cause discarded)
 * loses their context at the worst possible moment.
 */
@Composable
fun LiveErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LiveTheme.CardBackground)
            .border(1.dp, LiveTheme.DangerText, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("⚠", color = LiveTheme.DangerText, fontSize = 16.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Stream stopped",
                color = LiveTheme.DangerText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(message, color = LiveTheme.TextMuted, fontSize = 11.sp, maxLines = 2)
        }
        LiveSmallButton("Retry", onRetry)
        LiveSmallButton("Dismiss", onDismiss)
    }
}

object LiveTheme {
    val Background = Color(0xFFF7F7F3)
    val CardBackground = Color(0xFFFFFFFF)
    val CardBorder = Color(0xFFE3E1DB)
    val Accent = Color(0xFFFFC934)
    val AccentInk = Color(0xFF1A1A1A)
    val TextPrimary = Color(0xFF1A1A1A)
    val TextMuted = Color(0xFF6B6B6B)
    val DangerText = Color(0xFFD32F2F)

    /** "Starting…" on the live-status dot. Deliberately DESIGN.md's own light-mode status-pending
     *  value rather than a new invented amber — this screen's palette is a scoped exception, but a
     *  status colour should still come from the brand. Amber has to go this dark on a light surface
     *  to clear 4.5:1 (5.61:1 here); the dark-mode #ffb74d would fail badly. */
    val PendingText = Color(0xFF8F5300)
}

private fun formatElapsed(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun LiveCountdownSection(
    displaySeconds: Float,
    clockRunning: Boolean,
    onToggleRunning: () -> Unit,
    onEdit: () -> Unit,
    onAdjust: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two lines (label+time+play, then the adjust buttons) rather than one wide row — sharing
    // half the video strip's width with Shot Clock left too little room for a single row holding
    // all of it, which silently clipped the +30/Rst buttons off-screen.
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("COUNTDOWN", color = LiveTheme.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier.size(44.dp).clickable(onClick = onEdit)
                    .semantics { contentDescription = "Edit countdown clock" },
                contentAlignment = Alignment.Center,
            ) {
                Text("✎", color = LiveTheme.TextMuted, fontSize = 13.sp)
            }
            Text(
                displaySeconds.toClockString(),
                color = LiveTheme.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(LiveTheme.Accent)
                    .clickable(onClick = onToggleRunning)
                    .semantics { contentDescription = if (clockRunning) "Pause clock" else "Start clock" },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (clockRunning) "❚❚" else "▶", color = LiveTheme.AccentInk, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiveSmallButton("-30") { onAdjust(-30f) }
            LiveSmallButton("+30") { onAdjust(30f) }
            LiveSmallButton("Rst") { onReset() }
        }
    }
}

@Composable
fun LiveSmallButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(LiveTheme.CardBackground)
            .clickable(onClick = onClick)
            // 12sp text + 6dp padding left these at ~29dp, well under the 44dp floor PRODUCT.md
            // sets — and they're the live clock/shot-clock controls, tapped one-handed mid-match.
            // defaultMinSize (not .size) so the label still governs width when it's wider.
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = LiveTheme.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun LiveTeamCard(
    modifier: Modifier = Modifier,
    teamName: String,
    score: Int,
    setsWon: Int? = null,
    colorHex: String,
    scoreIncrements: List<Int>,
    onNameChange: (String) -> Unit,
    onScoreChange: (Int) -> Unit,
    onColorChange: (String) -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(LiveTheme.CardBackground)
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        var editingName by remember { mutableStateOf(false) }
        var localName by remember { mutableStateOf(teamName) }
        var hasFocusedOnce by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        LaunchedEffect(teamName) { if (!editingName) localName = teamName }
        // Auto-focus the moment the field opens, so tapping the pencil goes straight to typing —
        // and so onFocusChanged's close-on-unfocus below only fires on a REAL focus change, not
        // the field's initial (already-unfocused) composition frame. Without hasFocusedOnce, a
        // stray tap on the pencil (e.g. mid-scroll) could open the field and leave it stuck open
        // forever, since nothing else would ever change its focus state to trigger the commit.
        LaunchedEffect(editingName) {
            if (editingName) { hasFocusedOnce = false; focusRequester.requestFocus() }
        }

        fun commitAndClose() {
            editingName = false
            hasFocusedOnce = false
            onNameChange(localName)
        }

        if (editingName) {
            OutlinedTextField(
                value = localName,
                onValueChange = { localName = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) hasFocusedOnce = true
                        else if (hasFocusedOnce) commitAndClose()
                    },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(teamName, color = LiveTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Box(
                    modifier = Modifier.size(44.dp).clickable { editingName = true }
                        .semantics { contentDescription = "Edit $teamName name" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✎", color = LiveTheme.TextMuted, fontSize = 13.sp)
                }
                if (setsWon != null) {
                    Spacer(Modifier.width(4.dp))
                    Text("· Sets: $setsWon", color = LiveTheme.TextMuted, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(LiveTheme.AccentInk)
                    .clickable { onScoreChange(-1) }
                    .semantics { contentDescription = "Decrease $teamName score by 1" },
                contentAlignment = Alignment.Center,
            ) { Text("−", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }

            Spacer(Modifier.width(12.dp))

            Column {
                Text("POINT", color = LiveTheme.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(score.toString(), color = LiveTheme.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                scoreIncrements.forEach { inc ->
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(LiveTheme.Accent)
                            .clickable { onScoreChange(inc) },
                        contentAlignment = Alignment.Center,
                    ) { Text("+$inc", color = LiveTheme.AccentInk, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        TeamColorPicker(
            label = teamName,
            colorHex = colorHex,
            onColorChange = onColorChange,
            ringColor = LiveTheme.TextPrimary,
            caretColor = LiveTheme.TextMuted,
        )
    }
}

@Composable
fun LiveBottomBar(
    onStop: () -> Unit,
    streamerState: StreamerHolder.State,
    isRecording: Boolean,
    periodLabel: String,
    period: Int,
    maxPeriod: Int,
    onPeriodMinus: () -> Unit,
    onPeriodPlus: () -> Unit,
    batteryPct: Int,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    val liveStartedAtMs by StreamerHolder.liveStartedAtMs.collectAsState()
    LaunchedEffect(liveStartedAtMs) {
        if (liveStartedAtMs <= 0L) { elapsedSeconds = 0L; return@LaunchedEffect }
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - liveStartedAtMs) / 1000
            delay(1000)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(onClick = onStop),
        ) {
            Text("⏹", color = LiveTheme.DangerText, fontSize = 14.sp)
            Text("Stop", color = LiveTheme.TextPrimary, fontSize = 12.sp)
        }

        Column {
            // Broadcast state must be readable at a glance and must never assert "live" when it
            // isn't: this dot used to be hardcoded red regardless of state, so Starting, Live and
            // Idle-after-a-failure were pixel-identical. Colour is always paired with a word, per
            // DESIGN.md's Status Indicator rule.
            val (statusColor, statusLabel) = when (streamerState) {
                StreamerHolder.State.Live -> LiveTheme.DangerText to "LIVE"
                StreamerHolder.State.Starting -> LiveTheme.PendingText to "Starting…"
                is StreamerHolder.State.Error -> LiveTheme.DangerText to "Off air"
                StreamerHolder.State.Idle -> LiveTheme.TextMuted to "Off air"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("●", color = statusColor, fontSize = 10.sp)
                Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (streamerState is StreamerHolder.State.Live) {
                    Text(formatElapsed(elapsedSeconds), color = LiveTheme.TextPrimary, fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("●", color = if (isRecording) LiveTheme.DangerText else LiveTheme.TextMuted, fontSize = 8.sp)
                Text(
                    if (isRecording) "recording" else "video is not being stored",
                    color = LiveTheme.TextMuted, fontSize = 10.sp,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(LiveTheme.CardBackground)
                    .clickable(onClick = onPeriodMinus)
                    .semantics { contentDescription = "Previous $periodLabel" },
                contentAlignment = Alignment.Center,
            ) { Text("−", color = LiveTheme.TextPrimary, fontSize = 12.sp) }
            Text(
                "$period / $maxPeriod  ${periodLabel.uppercase()}/PERIOD",
                color = LiveTheme.TextPrimary, fontSize = 11.sp, textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(LiveTheme.CardBackground)
                    .clickable(onClick = onPeriodPlus)
                    .semantics { contentDescription = "Next $periodLabel" },
                contentAlignment = Alignment.Center,
            ) { Text("+", color = LiveTheme.TextPrimary, fontSize = 12.sp) }
        }

        Spacer(Modifier.weight(1f))

        Text("🔋$batteryPct%", color = LiveTheme.TextPrimary, fontSize = 12.sp)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(LiveTheme.Accent)
                .clickable(onClick = onShare)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("↗", color = LiveTheme.AccentInk, fontSize = 13.sp)
            Text("Share", color = LiveTheme.AccentInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
