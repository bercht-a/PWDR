package app.pwdr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.core.content.FileProvider
import app.pwdr.R
import app.pwdr.data.PwdrDatabase
import app.pwdr.data.RunEntity
import app.pwdr.data.TrackingRepository
import app.pwdr.tracking.Activity
import app.pwdr.tracking.Period
import app.pwdr.tracking.PeriodStats
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Minimale Tagesstatistik für Phase 1. Grafiken folgen in Phase 1b/2b (siehe PLAN.md, 4a). */
@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { PwdrDatabase.get(context) }
    val runs by db.runs().observeAll().collectAsState(initial = emptyList())
    val zone = remember { ZoneId.systemDefault() }

    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    val days = remember(runs) {
        runs.groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
            .toSortedMap(compareByDescending { it })
    }

    // Spur auf der Karte: "day" für den ganzen Tag oder die Id einer Abfahrt/eines Lifts.
    var track by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedDate = selected?.let { s -> days.keys.firstOrNull { it.toString() == s } }
    if (selectedDate != null) {
        val dayRuns = days.getValue(selectedDate)
        val trackKey = track
        if (trackKey != null) {
            TrackScreen(
                title = selectedDate.format(DAY_FORMAT),
                runs = dayRuns,
                runId = trackKey.toLongOrNull(),
                onBack = { track = null },
                modifier = modifier,
            )
        } else {
            DayDetailScreen(
                date = selectedDate,
                runs = dayRuns,
                onBack = { selected = null },
                onShowTrack = { id -> track = id?.toString() ?: "day" },
                modifier = modifier,
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        if (days.isEmpty()) {
            Text(
                text = stringResource(R.string.stats_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { PeriodCard(runs, zone) }
                items(days.entries.toList(), key = { it.key.toString() }) { (date, dayRuns) ->
                    DayCard(date, dayRuns, onClick = { selected = date.toString() })
                }
            }
        }
    }
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)

private const val MIN_RUN_VERT_DOWN_M = 10.0

@Composable
private fun DayCard(date: LocalDate, runs: List<RunEntity>, onClick: () -> Unit) {
    val downhills = runs.filter { it.type == Activity.DOWNHILL.name && it.vertDownM >= MIN_RUN_VERT_DOWN_M }
    val lifts = runs.filter { it.type == Activity.LIFT.name }
    val dist = downhills.sumOf { it.distanceM }
    val vert = downhills.sumOf { it.vertDownM }
    val vmax = downhills.maxOfOrNull { it.maxSpeedMs } ?: 0.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = date.format(DAY_FORMAT),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Summary(stringResource(R.string.label_runs), downhills.size.toString())
            Summary(stringResource(R.string.label_lifts), lifts.size.toString())
            Summary(stringResource(R.string.label_distance), formatDistance(dist))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Summary(stringResource(R.string.label_vert_down), formatMeters(vert))
            Summary(stringResource(R.string.label_vmax), "${kmh(vmax)} km/h")
            downhills.mapNotNull { it.maxG }.maxOrNull()?.let { Summary("Max. G", "${formatG(it)} g") }
        }
    }
}

/** Schreibt die Aufzeichnungen des Tages als GPX in den Cache und öffnet das Teilen-Menü. */
internal suspend fun shareGpx(context: Context, sessionIds: List<Long>, date: LocalDate) {
    val uri = try {
        withContext(Dispatchers.IO) {
            val repo = TrackingRepository(PwdrDatabase.get(context))
            val gpx = repo.exportGpx(sessionIds, "PWDR $date")
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "pwdr-$date.gpx")
            file.writeText(gpx)
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
    } catch (e: Exception) {
        Toast.makeText(context, R.string.stats_export_failed, Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun Summary(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Zusammenfassung über Woche, Monat oder Saison (ab 1. Oktober), einschliesslich G-Force. Die Wahl wird gemerkt. */
@Composable
private fun PeriodCard(runs: List<RunEntity>, zone: ZoneId) {
    val context = LocalContext.current
    var period by remember {
        mutableStateOf(
            try {
                Period.valueOf(context.getSharedPreferences("pwdr_prefs", Context.MODE_PRIVATE).getString("stats_period", null) ?: Period.SEASON.name)
            } catch (e: Exception) {
                Period.SEASON
            },
        )
    }
    val today = remember { LocalDate.now(zone) }
    var offset by remember { mutableStateOf(0) }
    var metric by remember {
        mutableStateOf(
            try {
                Metric.valueOf(context.getSharedPreferences("pwdr_prefs", Context.MODE_PRIVATE).getString("stats_bar_metric", null) ?: Metric.VERT.name)
            } catch (e: Exception) {
                Metric.VERT
            },
        )
    }
    val sum = remember(runs, period, offset) { PeriodStats.summarize(runs, period, today, zone, offset) }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Period.entries.forEach { p ->
                val on = p == period
                Text(
                    text = p.label,
                    modifier = Modifier
                        .weight(1f)
                        .background(if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(9.dp))
                        .clickable {
                            period = p
                            offset = 0
                            try {
                                context.getSharedPreferences("pwdr_prefs", Context.MODE_PRIVATE).edit().putString("stats_period", p.name).apply()
                            } catch (_: Exception) {
                            }
                        }
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { offset-- }) { Text("‹") }
            Text(
                period.title(today, offset),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextButton(onClick = { offset++ }, enabled = offset < 0) { Text("›") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Metric.entries.forEach { m ->
                val on = m == metric
                Text(
                    m.label,
                    Modifier
                        .weight(1f)
                        .background(if (on) m.color else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .clickable {
                            metric = m
                            try {
                                context.getSharedPreferences("pwdr_prefs", Context.MODE_PRIVATE).edit().putString("stats_bar_metric", m.name).apply()
                            } catch (_: Exception) {
                            }
                        }
                        .padding(vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        PeriodBars(PeriodStats.bars(runs, period, today, zone, offset), metric, perMonth = period == Period.SEASON)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Summary("Tage", sum.days.toString())
            Summary(stringResource(R.string.label_runs), sum.runs.toString())
            Summary(stringResource(R.string.label_vert_down), formatMeters(sum.vertDownM))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Summary(stringResource(R.string.label_distance), formatDistance(sum.distanceM))
            Summary(stringResource(R.string.label_vmax), "${kmh(sum.vmaxMs)} km/h")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Summary("Max. G", sum.maxG?.let { "${formatG(it)} g" } ?: "–")
            Summary("Ø G pro Abfahrt", sum.avgG?.let { "${formatG(it)} g" } ?: "–")
        }
        if (sum.maxG == null) {
            Text(
                "Noch keine G-Werte in diesem Zeitraum.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Balken je Tag (Woche, Monat) oder je Monat (Saison) für die gewählte Metrik. Ohne Werte erscheint ein Hinweis. */
@Composable
private fun PeriodBars(bars: List<PeriodStats.Bar>, metric: Metric, perMonth: Boolean) {
    fun value(b: PeriodStats.Bar): Double = when (metric) {
        Metric.VERT -> b.vertDownM
        Metric.DIST -> b.distanceM
        Metric.SPEED -> b.maxSpeedMs
        Metric.G -> b.maxG ?: 0.0
    }
    fun text(v: Double): String = when (metric) {
        Metric.VERT -> formatMeters(v)
        Metric.DIST -> formatDistance(v)
        Metric.SPEED -> "${kmh(v)} km/h"
        Metric.G -> "${formatG(v)} g"
    }
    val max = bars.maxOfOrNull { value(it) } ?: 0.0
    if (max <= 0.0) {
        Text(
            if (metric == Metric.G) "Keine G-Werte in diesem Zeitraum." else "Keine Abfahrten in diesem Zeitraum.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val color = metric.color
    val dense = bars.size > 12
    val unit = if (perMonth) "je Monat" else "je Tag"
    val caption = when (metric) {
        Metric.VERT -> "Höhenmeter bergab $unit"
        Metric.DIST -> "Distanz bergab $unit"
        Metric.SPEED -> "Höchstgeschwindigkeit $unit"
        Metric.G -> "Höchster G-Wert $unit (Richtwert)"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$caption · Spitze ${text(max)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(if (dense) 2.dp else 6.dp), verticalAlignment = Alignment.Bottom) {
            bars.forEach { b ->
                val v = value(b)
                Box(
                    Modifier.weight(1f).fillMaxHeight(if (v > 0) (v / max).toFloat().coerceAtLeast(0.04f) else 0.02f)
                        .background(if (v > 0) color else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (dense) 2.dp else 6.dp)) {
            bars.forEachIndexed { i, b ->
                // Bei vielen Balken nur jede fünfte Beschriftung, sonst wird es eng.
                val show = !dense || i % 5 == 0 || i == bars.lastIndex
                Text(
                    if (show) b.label else "",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
