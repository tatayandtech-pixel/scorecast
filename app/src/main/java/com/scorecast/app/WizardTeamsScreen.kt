package com.scorecast.app

import android.util.Size as AndroidSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.scorecast.app.overlay.ScoreboardOverlay

/** Spec Appendix B4 — wizard step 2/4: team names, colors, logos, and a live scoreboard preview. */
@Composable
fun WizardTeamsScreen(onBack: () -> Unit, onNext: () -> Unit) {
    val state by GameStateHolder.state.collectAsState()
    val logos by LogoHolder.logos.collectAsState()
    val config = remember(state.sport) { SportConfigLoader.getCached(state.sport) }

    // Reuses the exact same renderer that burns into the encoded stream, so this preview is
    // guaranteed to match what viewers will actually see.
    val previewBitmap = remember(state, logos, config) {
        ScoreboardOverlay.create(state, logos, config, AndroidSize(960, 540)).asImageBitmap()
    }

    WizardScaffold(step = 2, title = "Teams & scoreboard", onBack = onBack, onNext = onNext) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Image(
                bitmap = previewBitmap,
                contentDescription = "Scoreboard preview",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.background),
            )
            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TeamEditor(
                    modifier = Modifier.weight(1f),
                    label = "Home",
                    name = state.homeTeam,
                    colorHex = state.homeColorHex,
                    onNameChange = { GameStateHolder.update { copy(homeTeam = it) } },
                    onColorChange = { GameStateHolder.update { copy(homeColorHex = it) } },
                )
                TeamEditor(
                    modifier = Modifier.weight(1f),
                    label = "Away",
                    name = state.awayTeam,
                    colorHex = state.awayColorHex,
                    onNameChange = { GameStateHolder.update { copy(awayTeam = it) } },
                    onColorChange = { GameStateHolder.update { copy(awayColorHex = it) } },
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("Sponsor / team logos", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            LogoPanel()
        }
    }
}

@Composable
private fun TeamEditor(
    modifier: Modifier = Modifier,
    label: String,
    name: String,
    colorHex: String,
    onNameChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 20) onNameChange(it) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${name.length}/20",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 44dp tap target (PRODUCT.md's touch-target floor) wrapping a smaller 22dp visual dot,
        // plus horizontalScroll, since 8 full-size 44dp swatches side by side would overflow
        // this column's width.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            PRESET_COLORS.forEach { hex ->
                val argb = try {
                    android.graphics.Color.parseColor(hex)
                } catch (_: Exception) {
                    android.graphics.Color.GRAY
                }
                val selected = colorHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { onColorChange(hex) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
                    )
                }
            }
        }
    }
}
