package app.pwdr.map

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pwdr.social.ReportRow
import app.pwdr.social.ReportType
import app.pwdr.social.ReportsOnMap
import app.pwdr.social.Social
import app.pwdr.social.parseInstantMs
import app.pwdr.ui.parseColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

internal const val REPORTS_LAYER = "reports-dot"
internal const val REPORTS_APRES_LAYER = "reports-apres"

/** Meldungen als Punkte in der Farbe ihres Typs, mit dunklem Rand (Freunde haben einen weissen). */
@Composable
internal fun ReportsLayerEffect(style: Style?) {
    val reports by Social.reports.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    LaunchedEffect(style, reports, now) {
        val st = style ?: return@LaunchedEffect
        val data = FeatureCollection.fromJson(ReportsOnMap.geoJson(ReportsOnMap.active(reports, now)))
        val existing = st.getSourceAs<GeoJsonSource>("reports")
        if (existing != null) {
            existing.setGeoJson(data)
            return@LaunchedEffect
        }
        st.addSource(GeoJsonSource("reports", data))
        st.addImage(APRES_ICON, apresIcon())
        // Après-Ski mit Kaffee-Symbol, alle anderen Typen als farbiger Punkt
        st.addLayer(
            SymbolLayer(REPORTS_APRES_LAYER, "reports")
                .withFilter(Expression.eq(Expression.get("type"), Expression.literal("apres")))
                .withProperties(
                    PropertyFactory.iconImage(APRES_ICON),
                    PropertyFactory.iconSize(0.7f),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
        )
        st.addLayer(
            CircleLayer(REPORTS_LAYER, "reports")
                .withFilter(Expression.neq(Expression.get("type"), Expression.literal("apres")))
                .withProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleStrokeColor(android.graphics.Color.parseColor("#0E1317")),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }
}

/** Id der Meldung unter dem Fingertipp, oder null. */
internal fun reportIdAt(map: MapLibreMap, latLng: org.maplibre.android.geometry.LatLng): String? {
    val p = map.projection.toScreenLocation(latLng)
    val box = RectF(p.x - 28f, p.y - 28f, p.x + 28f, p.y + 28f)
    return map.queryRenderedFeatures(box, REPORTS_LAYER, REPORTS_APRES_LAYER).firstOrNull()?.getStringProperty("id")
}

/** Neue Meldung: Typ wählen, optional kurzer Text. Position ist der eigene Standort. */
@Composable
internal fun ReportComposeDialog(lat: Double, lon: Double, onDone: (String?) -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(ReportType.QUEUE) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Meldung an deinem Standort") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ReportType.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { type = t },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(t.label, Modifier.weight(1f))
                        Text("${t.ttlHours} h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(ReportsOnMap.MAX_TEXT) },
                    label = { Text("Text (optional)") },
                    supportingText = { Text("${text.length}/${ReportsOnMap.MAX_TEXT}. Sichtbar für alle PWDR-Nutzer in der Nähe, ohne Namen.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val error = Social.postReport(type, lat, lon, text)
                        busy = false
                        onDone(error)
                    }
                },
            ) { Text(if (busy) "Sende…" else "Melden") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Einzelne Meldung: Text, Alter, Restzeit, bewerten oder (eigene) löschen. */
@Composable
internal fun ReportDetailDialog(report: ReportRow, lat: Double, lon: Double, onResult: (String?) -> Unit, onDismiss: () -> Unit) {
    val type = ReportType.of(report.type)
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(block: suspend () -> String?) {
        busy = true
        scope.launch {
            val error = block()
            busy = false
            onResult(error)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.size(14.dp).background(parseColor(type.color), CircleShape))
                Text(type.label)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                report.text?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                val created = runCatching { parseInstantMs(report.createdAt) }.getOrNull()
                val expires = runCatching { parseInstantMs(report.expiresAt) }.getOrNull()
                Text(
                    listOfNotNull(
                        created?.let { ReportsOnMap.ageText(it, now) },
                        expires?.let { ReportsOnMap.remainingText(it, now) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (report.mine) {
                    Text("Deine Meldung", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "${report.upVotes} bestätigt, ${report.downVotes} widersprochen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VoteButton("Stimmt (${report.upVotes})", report.myVote == 1, !busy, Modifier.weight(1f)) {
                            run { Social.voteReport(report.id, if (report.myVote == 1) 0 else 1, lat, lon) }
                        }
                        VoteButton("Stimmt nicht (${report.downVotes})", report.myVote == -1, !busy, Modifier.weight(1f)) {
                            run { Social.voteReport(report.id, if (report.myVote == -1) 0 else -1, lat, lon) }
                        }
                    }
                    Text(
                        "Bei mindestens drei Widersprüchen wird eine Meldung ausgeblendet.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schliessen") } },
        dismissButton = if (report.mine) {
            { TextButton(enabled = !busy, onClick = { run { Social.deleteReport(report.id, lat, lon) } }) { Text("Löschen") } }
        } else null,
    )
}

@Composable
private fun VoteButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label, style = MaterialTheme.typography.labelMedium) }
    else OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label, style = MaterialTheme.typography.labelMedium) }
}
