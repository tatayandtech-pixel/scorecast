package com.scorecast.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared "Step X of 4" header + Back/Next bar for the setup wizard (spec Appendix B3-B6). */
@Composable
fun WizardScaffold(
    step: Int,
    title: String,
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean = true,
    nextLabel: String = "Next",
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            "Step $step of 4",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            content()
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = onNext, enabled = nextEnabled, modifier = Modifier.weight(1f)) { Text(nextLabel) }
        }
    }
}
