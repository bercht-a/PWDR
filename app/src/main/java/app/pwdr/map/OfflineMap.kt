package app.pwdr.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/**
 * Aktive Downloads müssen von der App festgehalten werden. MapLibre beendet den Download, sobald das Region-Objekt vom
 * Speicherbereiniger entfernt wird (der Download blieb sonst bei 0 Prozent stehen).
 */
private val keepAlive = mutableListOf<OfflineRegion>()

/** Eine gespeicherte Offline-Region mit Name, Fortschritt (0 bis 1) und Grösse in Bytes. */
private data class SavedRegion(val region: OfflineRegion, val name: String, val progress: Float, val bytes: Long, val complete: Boolean)

private fun metaName(region: OfflineRegion): String = try {
    JSONObject(String(region.metadata, Charsets.UTF_8)).optString("name", "Ausschnitt")
} catch (_: Exception) {
    "Ausschnitt"
}

private fun mb(bytes: Long): String =
    if (bytes < 1_000_000) "${max(1L, bytes / 1000)} KB" else String.format(Locale.GERMAN, "%.0f MB", bytes / 1_000_000.0)

private fun percent(s: OfflineRegionStatus): Float =
    if (s.requiredResourceCount <= 0) 0f else (s.completedResourceCount.toFloat() / s.requiredResourceCount).coerceIn(0f, 1f)

/**
 * Karte für unterwegs ohne Netz laden. Gespeichert wird der Ausschnitt, der gerade auf der Karte zu sehen ist, in der
 * gewählten Ansicht (Karte, Luftbild oder grau, dazu optional die Hangneigung). Die Kacheln liegen im Zwischenspeicher
 * der Karte, die Karte findet sie ohne weiteres Zutun, sobald das Netz fehlt. Pisten und Lifte sind schon lokal gespeichert.
 * Freunde, Meldungen, Wetter und das Bulletin brauchen weiterhin Netz.
 */
@Composable
internal fun OfflineMapDialog(map: MapLibreMap?, base: BaseMap, slope: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { OfflineManager.getInstance(context.applicationContext).also { it.setOfflineMapboxTileCountLimit(50_000) } }
    val saved = remember { mutableStateListOf<SavedRegion>() }
    var refreshGen by remember { mutableStateOf(0) }
    var maxZoom by remember { mutableStateOf(15) }
    var message by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf<Float?>(null) }

    val bounds: LatLngBounds? = remember(map) {
        try {
            map?.projection?.visibleRegion?.latLngBounds
        } catch (_: Exception) {
            null
        }
    }
    val zoom = remember(map) { map?.cameraPosition?.zoom ?: 0.0 }
    val minZoom = max(5, floor(zoom).toInt() - 4)
    val limit = OfflineTiles.MAX_TILES.toLong() * OfflineTiles.sources(slope)

    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                // Erst alle Stände einsammeln, dann die Liste in einem Zug ersetzen (sonst doppeln sich Einträge bei überlappenden Abfragen).
                val regions = offlineRegions ?: emptyArray()
                val gen = ++refreshGen
                if (regions.isEmpty()) {
                    saved.clear()
                    return
                }
                val found = ArrayList<SavedRegion>()
                var answered = 0
                fun done() {
                    answered++
                    if (answered == regions.size && gen == refreshGen) {
                        saved.clear()
                        saved.addAll(found.sortedBy { it.region.id })
                    }
                }
                regions.forEach { r ->
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status != null) found.add(SavedRegion(r, metaName(r), percent(status), status.completedResourceSize, status.isComplete))
                            done()
                        }

                        override fun onError(error: String?) = done()
                    })
                }
            }

            override fun onError(error: String) {}
        })
    }
    LaunchedEffect(Unit) {
        LocalStyleServer.start(context)
        refresh()
    }

    fun tilesFor(top: Int): Long =
        bounds?.let { OfflineTiles.count(it.latitudeSouth, it.longitudeWest, it.latitudeNorth, it.longitudeEast, minZoom, top) }
            ?.times(OfflineTiles.sources(slope)) ?: 0L

    fun observe(region: OfflineRegion) {
        if (keepAlive.none { it.id == region.id }) keepAlive.add(region)
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                running = if (status.isComplete) null else percent(status)
                if (status.isComplete) {
                    message = "Fertig: ${mb(status.completedResourceSize)} gespeichert."
                    refresh()
                }
            }

            override fun onError(error: OfflineRegionError) {
                message = "Download gestört (${error.reason}): ${error.message}"
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                message = "Zu viele Kacheln (Grenze $limit). Zoome näher heran."
                running = null
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    fun start() {
        val b = bounds ?: return
        if (!LocalStyleServer.start(context)) {
            message = "Der lokale Stil-Server konnte nicht starten (Port belegt)."
            return
        }
        val def = OfflineTilePyramidRegionDefinition(LocalStyleServer.url(base, slope), b, minZoom.toDouble(), maxZoom.toDouble(), 1f)
        val stamp = SimpleDateFormat("d.M. HH:mm", Locale.GERMAN).format(Date())
        val name = "${base.label}${if (slope) " + Hangneigung" else ""}, Stufe $minZoom–$maxZoom ($stamp)"
        val meta = JSONObject().put("name", name).toString().toByteArray()
        running = 0f
        message = null
        manager.createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) = observe(offlineRegion)

            override fun onError(error: String) {
                running = null
                message = "Download nicht gestartet: $error"
            }
        })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline-Karte") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Speichert den Kartenausschnitt, den du gerade siehst, in der gewählten Ansicht (${base.label}${if (slope) " mit Hangneigung" else ""}). " +
                        "Pisten und Lifte sind schon auf dem Handy. Freunde, Meldungen, Wetter und Lawinen brauchen weiterhin Netz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (bounds == null) {
                    Text("Die Karte ist noch nicht bereit.")
                } else {
                    Text("Wie genau? Die Grösse ist ungefähr, am besten im WLAN laden.", style = MaterialTheme.typography.labelLarge)
                    listOf(14 to "Übersicht", 15 to "Detail", 16 to "Sehr genau").forEach { (z, label) ->
                        val tiles = tilesFor(z)
                        val ok = tiles in 1..limit
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = maxZoom == z, onClick = { maxZoom = z }, enabled = ok)
                            Column {
                                Text(label, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (ok) {
                                        "ca. ${mb(OfflineTiles.estimateBytes(tiles / OfflineTiles.sources(slope), base, slope))}, $tiles Kacheln"
                                    } else {
                                        "zu gross für diesen Ausschnitt, näher heranzoomen"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                running?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                    Text("Lädt … ${(it * 100).toInt()} %", style = MaterialTheme.typography.labelMedium)
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                if (saved.isNotEmpty()) {
                    Text("Gespeichert", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    saved.forEach { s ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (s.complete) mb(s.bytes) else "unvollständig (${(s.progress * 100).toInt()} %), ${mb(s.bytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!s.complete) TextButton(onClick = { running = s.progress; observe(s.region) }) { Text("Weiter") }
                            TextButton(onClick = {
                                s.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                    override fun onDelete() {
                                        keepAlive.removeAll { it.id == s.region.id }
                                        refresh()
                                    }

                                    override fun onError(error: String) {
                                        message = "Löschen fehlgeschlagen: $error"
                                    }
                                })
                            }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { start() }, enabled = bounds != null && running == null && tilesFor(maxZoom) in 1..limit) { Text("Laden") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schliessen") } },
    )
}
