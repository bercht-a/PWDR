package app.pwdr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
    val sum = remember(runs, period) { PeriodStats.summarize(runs, period, today, zone) }
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
