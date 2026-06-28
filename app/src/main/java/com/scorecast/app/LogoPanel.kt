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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogoPanel() {
    val context = LocalContext.current
    val logos by LogoHolder.logos.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) LogoHolder.addLogo(context, uri)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Text("Logos", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = { picker.launch("image/*") },
                modifier = Modifier.height(28.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(4.dp),
            ) { Text("+ Add", fontSize = 11.sp) }
        }

        if (logos.isEmpty()) {
            Text("No logos — tap + Add to pick a PNG/JPG",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp))
        }

        logos.forEach { logo ->
            LogoRow(logo = logo, onRemove = { LogoHolder.removeLogo(logo.id) })
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun LogoRow(logo: LogoEntry, onRemove: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Text("Logo ${logo.id.take(6)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier.height(24.dp).size(width = 56.dp, height = 24.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                shape = RoundedCornerShape(4.dp),
            ) { Text("Remove", fontSize = 9.sp) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("X", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(12.dp))
            Slider(
                value = logo.normalizedX,
                onValueChange = { LogoHolder.updatePosition(logo.id, it, logo.normalizedY) },
                valueRange = 0f..0.85f,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Y", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(12.dp))
            Slider(
                value = logo.normalizedY,
                onValueChange = { LogoHolder.updatePosition(logo.id, logo.normalizedX, it) },
                valueRange = 0f..0.85f,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sz", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(12.dp))
            Slider(
                value = logo.scale,
                onValueChange = { LogoHolder.updateScale(logo.id, it) },
                valueRange = 0.2f..3f,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
