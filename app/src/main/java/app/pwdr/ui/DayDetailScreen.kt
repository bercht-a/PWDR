package app.pwdr.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwdr.R
import app.pwdr.data.PwdrDatabase
import app.pwdr.data.toSample
import app.pwdr.data.RunEntity
import app.pwdr.data.TrackingRepository
import app.pwdr.tracking.TrackingStore
import app.pwdr.tracking.Activity
import app.pwdr.tracking.ActivityRange
import app.pwdr.tracking.DayProfile
import app.pwdr.tracking.ProfilePoint
import app.pwdr.tracking.Sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MIN_RUN_VERT_DOWN_M = 10.0
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)

private const val PREFS = "pwdr_prefs"
private const val KEY_METRIC = "stats_metric"

private fun loadMetric(context: Context): Metric {
    val name = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_METRIC, null)
    } catch (e: Exception) {
        null
    }
    return Metric.entries.firstOrNull { it.name == name } ?: Metric.VERT
}

private fun saveMetric(context: Context, metric: Metric) {
    try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_METRIC, metric.name).apply()
    } catch (_: Exception) {
    }
}

/** Tagesansicht: Kacheln, Verlaufsgrafik mit Metrik-Umschalter, Abfahrts- und Liftliste. Siehe PLAN.md 4a. */
@Composable
fun DayDetailScreen(
    date: LocalDate,
    runs: List<RunEntity>,
    onBack: () -> Unit,
    onShowTrack: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    var metric by remember { mutableStateOf(loadMetric(context)) }
    var profile by remember { mutableStateOf<List<ProfilePoint>?>(null) }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var sessionInfo by remember { mutableStateOf<List<app.pwdr.data.SessionEntity>>(emptyList()) }
    var showGSettings by remember { mutableStateOf(false) }

    LaunchedEffect(runs) {
        sessionInfo = withContext(Dispatchers.IO) {
            val repo = TrackingRepository(PwdrDatabase.get(context))
            runs.map { it.sessionId }.distinct().sorted().mapNotNull { repo.session(it) }
        }
    }

    if (showGSettings) GForceSettingsDialog(recording = TrackingStore.recording.value, onDismiss = { showGSettings = false })

    LaunchedEffect(runs) {
        profile = withContext(Dispatchers.IO) {
            val db = PwdrDatabase.get(context)
            val sessions = runs.map { it.sessionId }.distinct().sorted().map { id ->
                db.points().forSession(id).map {
                    it.toSample()
                }
            }
            val ranges = runs.map { ActivityRange(Activity.valueOf(it.type), it.startMs, it.endMs) }
            DayProfile.build(sessions, ranges)
        }
    }

    if (confirmDelete) {
        val downs = runs.count { it.type == Activity.DOWNHILL.name && it.vertDownM >= MIN_RUN_VERT_DOWN_M }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Tag löschen?") },
            text = {
                Text(
                    "${date.format(DAY_FORMAT)}: ${if (downs == 1) "1 Abfahrt" else "$downs Abfahrten"} mit allen Aufzeichnungen und Messpunkten werden endgültig " +
                        "gelöscht. Das lässt sich nicht rückgängig machen. Exportiere vorher die GPX-Datei, wenn du sie behalten willst.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            TrackingRepository(PwdrDatabase.get(context)).deleteSessions(runs.map { it.sessionId }.distinct())
                        }
                        onBack()
                    }
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }

    val downhills = runs.filter { it.type == Activity.DOWNHILL.name && it.vertDownM >= MIN_RUN_VERT_DOWN_M }
    val lifts = runs.filter { it.type == Activity.LIFT.name }
    val items = (downhills.map { it to true } + lifts.map { it to false }).sortedBy { it.first.startMs }
    val maxByMetric = when (metric) {
        Metric.VERT -> downhills.maxOfOrNull { it.vertDownM }
        Metric.DIST -> downhills.maxOfOrNull { it.distanceM }
        Metric.SPEED -> downhills.maxOfOrNull { it.maxSpeedMs }
        Metric.G -> downhills.mapNotNull { it.maxG }.maxOrNull()
    }?.takeIf { it > 0 } ?: 1.0

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                TextButton(onClick = { onShowTrack(null) }) { Text(stringResource(R.string.track_day)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (TrackingStore.recording.value) {
                        Toast.makeText(context, "Während der Aufzeichnung kann nichts gelöscht werden.", Toast.LENGTH_SHORT).show()
                    } else {
                        confirmDelete = true
                    }
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { scope.launch { shareGpx(context, runs.map { it.sessionId }.distinct(), date) } }) {
                    Text(stringResource(R.string.stats_export_gpx))
                }
            }
            Text(
                text = date.format(DAY_FORMAT),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        item { DaySummary(downhills, lifts) }
        item {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricSwitch(metric) {
                    metric = it
                    saveMetric(context, it)
                }
                val p = profile
                when {
                    p == null -> Spacer(Modifier.height(180.dp))
                    p.size < 2 -> Text(
                        stringResource(R.string.stats_no_profile),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    metric == Metric.G && p.none { it.g != null } -> Text(
                        "Für diesen Tag gibt es keine G-Werte. Sie werden erst seit Version 0.1.0-alpha.4 aufgezeichnet und fehlen ohne Beschleunigungssensor.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> DayChart(p, metric)
                }
                if (metric == Metric.G) {
                    Text(
                        "G-Force ist ein Richtwert. Die Werte hängen davon ab, wo das Handy getragen wird (Hosentasche oder Brust), und lassen sich nur innerhalb der eigenen Fahrten vergleichen. 1,0 g ist die Ruhe.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val strongest = downhills.filter { it.maxG != null }.maxByOrNull { it.maxG ?: 0.0 }
                    if (strongest != null && (strongest.maxG ?: 0.0) >= app.pwdr.tracking.GForceStats.MIN_PEAK_G) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Stärkste Kurve: ${formatG(strongest.maxG ?: 0.0)} g · ${strongest.name ?: stringResource(R.string.unknown_piste)} · " +
                                    Instant.ofEpochMilli(strongest.startMs).atZone(zone).format(TIME_FORMAT),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = metric.color,
                            )
                            TextButton(onClick = { onShowTrack(strongest.id) }) { Text("Auf Karte") }
                        }
                    }
                    TextButton(onClick = { showGSettings = true }) { Text("G-Force-Einstellungen") }
                }
                Legend(metric.color)
            }
        }
        item {
            Text(
                text = stringResource(R.string.stats_runs_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        var runNo = 0
        for ((run, isDownhill) in items) {
            val no = if (isDownhill) ++runNo else 0
            item(key = run.id) {
                if (isDownhill) {
                    DownhillRow(no, run, metric, maxByMetric, zone) { onShowTrack(run.id) }
                } else {
                    LiftRow(run, zone) { onShowTrack(run.id) }
                }
            }
        }
        if (sessionInfo.isNotEmpty()) {
            item { SessionInfoCard(sessionInfo, zone) }
        }
        item {
            Text(
                text = stringResource(R.string.osm_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DaySummary(downhills: List<RunEntity>, lifts: List<RunEntity>) {
    val dist = downhills.sumOf { it.distanceM }
    val vert = downhills.sumOf { it.vertDownM }
    val vertUp = lifts.sumOf { it.vertUpM }
    val vmax = downhills.maxOfOrNull { it.maxSpeedMs } ?: 0.0
    val skiMs = downhills.sumOf { it.endMs - it.startMs }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Tile(Modifier.weight(1f), stringResource(R.string.label_vert_down), formatMeters(vert))
            Tile(Modifier.weight(1f), stringResource(R.string.label_distance), formatDistance(dist))
            Tile(Modifier.weight(1f), stringResource(R.string.label_vmax), "${kmh(vmax)} km/h")
        }
        Row(Modifier.fillMaxWidth()) {
            Tile(Modifier.weight(1f), stringResource(R.string.label_runs), downhills.size.toString())
            Tile(Modifier.weight(1f), stringResource(R.string.label_lifts), lifts.size.toString())
            Tile(Modifier.weight(1f), stringResource(R.string.label_vert_up), formatMeters(vertUp))
        }
        Row(Modifier.fillMaxWidth()) {
            Tile(Modifier.weight(1f), stringResource(R.string.label_ski_time), formatDuration(skiMs))
            val avg = if (skiMs > 0) dist / (skiMs / 1000.0) else 0.0
            Tile(Modifier.weight(1f), stringResource(R.string.label_avg_speed), "${kmh(avg)} km/h")
            val maxG = downhills.mapNotNull { it.maxG }.maxOrNull()
            Tile(Modifier.weight(1f), "Max. G", maxG?.let { "${formatG(it)} g" } ?: "–")
        }
    }
}


@Composable
private fun Tile(modifier: Modifier, label: String, value: String) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MetricSwitch(selected: Metric, onSelect: (Metric) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Metric.entries.forEach { m ->
            val on = m == selected
            Text(
                text = m.label,
                modifier = Modifier
                    .weight(1f)
                    .background(if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(9.dp))
                    .clickable { onSelect(m) }
                    .padding(vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Legend(downhillColor: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem(downhillColor, stringResource(R.string.legend_downhill))
        LegendItem(MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.legend_lift))
        LegendItem(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), stringResource(R.string.legend_pause))
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(14.dp).height(3.dp).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownhillRow(no: Int, r: RunEntity, metric: Metric, max: Double, zone: ZoneId, onClick: () -> Unit) {
    val time = Instant.ofEpochMilli(r.startMs).atZone(zone).format(TIME_FORMAT)
    val value = when (metric) {
        Metric.VERT -> r.vertDownM
        Metric.DIST -> r.distanceM
        Metric.SPEED -> r.maxSpeedMs
        Metric.G -> r.maxG ?: 0.0
    }
    val valueText = when (metric) {
        Metric.VERT -> formatMeters(r.vertDownM)
        Metric.DIST -> formatDistance(r.distanceM)
        Metric.SPEED -> "${kmh(r.maxSpeedMs)} km/h"
        Metric.G -> r.maxG?.let { "${formatG(it)} g" } ?: "–"
    }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(difficultyColor(r.difficulty), androidx.compose.foundation.shape.CircleShape))
                Text(
                    "$no.  ${r.name ?: stringResource(R.string.unknown_piste)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = metric.color,
            )
        }
        BarFill((value / max).toFloat(), metric.color)
        Text(
            "$time · ${formatDuration(r.endMs - r.startMs)} · ${formatMeters(r.vertDownM)} · ${formatDistance(r.distanceM)} · ${kmh(r.maxSpeedMs)} km/h" + (r.maxG?.let { " · ${formatG(it)} g" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Pistenfarben wie auf Schweizer Pistenplänen: blau leicht, rot mittel, schwarz schwer (im Dunkelmodus hellgrau). */
@Composable
private fun difficultyColor(difficulty: String?): androidx.compose.ui.graphics.Color = when (difficulty) {
    "novice", "easy" -> androidx.compose.ui.graphics.Color(0xFF2F8FFF)
    "intermediate" -> androidx.compose.ui.graphics.Color(0xFFE5484D)
    "advanced", "expert", "freeride" -> androidx.compose.ui.graphics.Color(0xFFB0B8BF)
    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
}

@Composable
private fun LiftRow(r: RunEntity, zone: ZoneId, onClick: () -> Unit) {
    val time = Instant.ofEpochMilli(r.startMs).atZone(zone).format(TIME_FORMAT)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "${r.name ?: stringResource(R.string.legend_lift)}  $time",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${formatDuration(r.endMs - r.startMs)} · +${formatMeters(r.vertUpM)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/** Aufzeichnungen des Tages mit G-Force-Einstellung und Akkuverbrauch (zum Vergleichen mit und ohne G-Force). */
@Composable
private fun SessionInfoCard(sessions: List<app.pwdr.data.SessionEntity>, zone: ZoneId) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Aufzeichnungen", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        sessions.forEach { s ->
            val start = Instant.ofEpochMilli(s.startMs).atZone(zone).format(TIME_FORMAT)
            val end = s.endMs?.let { Instant.ofEpochMilli(it).atZone(zone).format(TIME_FORMAT) } ?: "–"
            val g = when (s.gForce) {
                true -> "G-Force an (${app.pwdr.tracking.CarryPosition.of(s.carry).label})"
                false -> "G-Force aus"
                null -> "G-Force: keine Angabe"
            }
            val battery = app.pwdr.tracking.BatteryText.describe(s.batteryStartPct, s.batteryEndPct, (s.endMs ?: s.startMs) - s.startMs)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$start – $end · $g", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                battery?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Text(
            "Der Akkuwert gilt für das ganze Handy (Bildschirm, GPS, Netz), nicht nur für PWDR. Für den Einfluss von G-Force eine Fahrt mit und eine ohne vergleichen.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
