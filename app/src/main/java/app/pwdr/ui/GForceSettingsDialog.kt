package app.pwdr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pwdr.tracking.CarryPosition
import app.pwdr.tracking.GForcePrefs

/**
 * Einstellungen für G-Force: Sensor ein oder aus und Trageposition. Die Änderung gilt ab der nächsten Aufzeichnung.
 * Mit ausgeschaltetem Sensor lässt sich der Akkuverbrauch vergleichen (siehe Tagesansicht, Aufzeichnungen).
 */
@Composable
fun GForceSettingsDialog(recording: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { GForcePrefs(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var position by remember { mutableStateOf(prefs.position) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("G-Force") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("G-Force aufzeichnen")
                        Text(
                            "Nutzt den Beschleunigungssensor. Ausschalten spart etwas Akku.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it; prefs.enabled = it })
                }
                Text("Wo trägst du das Handy?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                CarryPosition.entries.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = enabled) { position = p; prefs.position = p },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = position == p, onClick = { position = p; prefs.position = p }, enabled = enabled)
                        Column {
                            Text(p.label)
                            Text(p.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "Die Position ändert die Werte. G-Force ist ein Richtwert und lässt sich nur mit Fahrten vergleichen, bei denen das Handy gleich getragen wurde.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (recording) {
                    Text(
                        "Gilt ab der nächsten Aufzeichnung, nicht für die laufende.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}
