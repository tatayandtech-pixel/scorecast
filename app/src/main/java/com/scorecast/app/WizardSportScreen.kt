package com.scorecast.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Spec Appendix B3 — wizard step 1/4: sport selection tile grid, rendered from installed configs. */
@Composable
fun WizardSportScreen(onBack: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    val configs = remember { SportConfigLoader.loadAll(context) }
    val state by GameStateHolder.state.collectAsState()

    WizardScaffold(step = 1, title = "Choose a sport", onBack = onBack, onNext = onNext) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(configs, key = { it.sport }) { cfg ->
                val selected = cfg.sport == state.sport
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { GameStateHolder.selectSport(cfg) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(cfg.displayName, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
