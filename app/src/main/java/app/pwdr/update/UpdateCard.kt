package app.pwdr.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Hinweis auf eine neuere Version. Zeigt nichts, wenn es keine gibt. */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val info by UpdateChecker.available.collectAsState()
    val u = info ?: return
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Neue Version ${u.version} verfügbar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Lade die Datei herunter und tippe sie an, um zu aktualisieren. Deine Fahrten und Einstellungen bleiben erhalten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                    }
                }) { Text(if (u.apkUrl.isNotEmpty()) "Herunterladen" else "Zur Seite") }
                TextButton(onClick = { UpdateChecker.dismiss() }) { Text("Später") }
            }
        }
    }
}

/** Einstellung im Freunde-Tab: automatische Prüfung ein/aus und Prüfung von Hand. */
@Composable
fun UpdateSettings(modifier: Modifier = Modifier) {
    val enabled by UpdateChecker.enabled.collectAsState()
    val status by UpdateChecker.status.collectAsState()
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Einmal am Tag nach einer neuen Version suchen (GitHub, ohne persönliche Angaben)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = { UpdateChecker.setEnabled(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { scope.launch { UpdateChecker.checkNow() } }) { Text("Jetzt prüfen") }
            if (status != null) {
                Text(status.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
