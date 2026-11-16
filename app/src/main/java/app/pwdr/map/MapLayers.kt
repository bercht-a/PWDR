package app.pwdr.map

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

/** Schaltet die Grundkarte und die Hangneigung über die Sichtbarkeit um. Pisten, Freunde und Meldungen bleiben stehen. */
internal fun applyBaseMap(style: Style, base: BaseMap, slope: Boolean) {
    fun vis(on: Boolean) = PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
    BaseMap.entries.forEach { b -> style.getLayer(b.layerId)?.setProperties(vis(b == base)) }
    style.getLayer(SwisstopoStyle.SLOPE_LAYER)?.setProperties(vis(slope))
}

/** Auswahl der Kartenansicht. */
@Composable
internal fun MapLayersDialog(
    base: BaseMap,
    slope: Boolean,
    avalanche: Boolean,
    onBase: (BaseMap) -> Unit,
    onSlope: (Boolean) -> Unit,
    onAvalanche: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kartenansicht") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BaseMap.entries.forEach { b ->
                    Row(Modifier.fillMaxWidth().clickable { onBase(b) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = base == b, onClick = { onBase(b) })
                        Text(b.label)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hangneigung ab 30°")
                        Text(
                            "Ebene von swisstopo über der Karte. Keine Lawinenbeurteilung.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = slope, onCheckedChange = onSlope)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lawinenregionen (SLF)")
                        Text(
                            "Gefahrenstufe je Region nach dem SLF-Bulletin, Tipp auf eine Region zeigt Details. Braucht Netz, nur eine Übersicht.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = avalanche, onCheckedChange = onAvalanche)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}
