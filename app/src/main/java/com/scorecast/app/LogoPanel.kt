package com.scorecast.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MAX_LOGOS_PER_SLOT = 6

// Owner request: no more free X/Y/size sliders — logos are fixed to one of two corners, sized to
// a fixed box (see ScoreboardOverlay.LOGO_BOX_WIDTH_FRACTION). Two or more logos in the same
// corner rotate automatically (ScoreboardOverlay.drawLogos); this panel just manages membership.
@Composable
fun LogoPanel() {
    val logos by LogoHolder.logos.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        LogoSlotSection(
            title = "Logo (top-left)",
            slot = LogoSlot.TOP_LEFT,
            logos = logos.filter { it.slot == LogoSlot.TOP_LEFT },
        )
        Spacer(Modifier.height(8.dp))
        LogoSlotSection(
            title = "Sponsor logo (top-right)",
            slot = LogoSlot.TOP_RIGHT,
            logos = logos.filter { it.slot == LogoSlot.TOP_RIGHT },
        )
    }
}

@Composable
private fun LogoSlotSection(title: String, slot: LogoSlot, logos: List<LogoEntry>) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) LogoHolder.addLogo(context, uri, slot)
    }

    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { picker.launch("image/*") },
            enabled = logos.size < MAX_LOGOS_PER_SLOT,
            modifier = Modifier.height(44.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            shape = RoundedCornerShape(4.dp),
        ) { Text("+ Add", fontSize = 11.sp) }
    }

    if (logos.size >= MAX_LOGOS_PER_SLOT) {
        Text("Maximum of $MAX_LOGOS_PER_SLOT logos in this spot",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp))
    } else if (logos.isEmpty()) {
        Text("No logos — tap + Add to pick a PNG/JPG",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp))
    } else if (logos.size >= 2) {
        Text("${logos.size} logos here — they'll take turns every 10s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp))
    }

    logos.forEach { logo ->
        LogoRow(logo = logo, onRemove = { LogoHolder.removeLogo(logo.id) })
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun LogoRow(logo: LogoEntry, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("Logo ${logo.id.take(6)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        OutlinedButton(
            onClick = onRemove,
            modifier = Modifier.height(44.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            shape = RoundedCornerShape(4.dp),
        ) { Text("Remove", fontSize = 9.sp) }
    }
}
