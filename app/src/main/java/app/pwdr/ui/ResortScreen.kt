package app.pwdr.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pwdr.resort.AvalancheApi
import app.pwdr.resort.AvalancheBulletin
import app.pwdr.resort.AvalancheStatus
import app.pwdr.resort.ResortData
import app.pwdr.resort.ResortProfile
import app.pwdr.resort.ResortProfiles
import app.pwdr.resort.Section
import app.pwdr.resort.SnowStationReport
import app.pwdr.resort.Webcam
import app.pwdr.resort.WeatherApi
import app.pwdr.resort.WeatherReport
import app.pwdr.resort.WeatherSite
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
private val DE = Locale.GERMAN

/**
 * Gebietsseite (PLAN.md 5d und 5e): Lawinenbulletin ganz oben, dann Wetter, Schnee, Webcams und Quellen.
 * Jede Quelle fällt einzeln aus, jeder Wert zeigt sein Alter.
 */
@Composable
fun ResortScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var resort by remember { mutableStateOf(ResortProfiles.byId(loadResortId(context))) }
    LaunchedEffect(resort.id) { ResortData.load(context, resort) }

    val weather by ResortData.weather.collectAsState()
    val snow by ResortData.snow.collectAsState()
    val avalanche by ResortData.avalanche.collectAsState()

    LazyColumn(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Zurück") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { ResortData.load(context, resort, force = true) }) { Text("Aktualisieren") }
            }
            Text(resort.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResortProfiles.ALL.forEach { p ->
                    val on = p.id == resort.id
                    Text(
                        p.name,
                        Modifier
                            .background(
                                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { resort = p; saveResortId(context, p.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        item { AvalancheCard(avalanche) }
        item { WeatherCard(weather) }
        item { SnowCard(snow, weather) }
        item { WebcamCard(resort) }
        item { SourcesCard() }
    }
}

// ------------------------------------------------------------------ Gemeinsame Teile

@Composable
private fun Card(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
}

@Composable
private fun LinkButton(text: String, url: String) {
    val context = LocalContext.current
    TextButton(onClick = { openUrl(context, url) }) { Text(text) }
}

/** "Stand vor 3 min", bei gespeichertem Stand mit Hinweis auf fehlendes Netz. */
private fun ageText(fetchedAtMs: Long, fromCache: Boolean): String {
    val min = ((System.currentTimeMillis() - fetchedAtMs) / 60_000).coerceAtLeast(0)
    val age = when {
        min < 1 -> "gerade eben"
        min < 60 -> "vor $min min"
        min < 48 * 60 -> "vor ${min / 60} h"
        else -> "vor ${min / (24 * 60)} Tagen"
    }
    return "Stand $age" + if (fromCache) " (gespeichert, kein Netz)" else ""
}

fun openUrl(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    } catch (e: Exception) {
        // Kein Browser mit Custom Tabs: normaler Aufruf
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun loadResortId(context: Context): String? = try {
    context.getSharedPreferences("pwdr_prefs", Context.MODE_PRIVATE).getString("resort_id", null)
} catch (e: Exception) {
    null
}

private fun saveResortId(context: Context, id: String) {
    try {
        context.getSharedPreferences("pwdr_prefs", Context.MODE_PRIVATE).edit().putString("resort_id", id).apply()
    } catch (_: Exception) {
    }
}

// ------------------------------------------------------------------ Lawinen

/** Farben der Gefahrenstufen nach europäischem Standard, immer zusammen mit der Ziffer. */
private fun dangerColor(level: Int): Color = when (level) {
    1 -> Color(0xFFCCFF66)
    2 -> Color(0xFFFFFF00)
    3 -> Color(0xFFFF9900)
    4 -> Color(0xFFFF0000)
    5 -> Color(0xFF7A0000)
    else -> Color(0xFF888888)
}

@Composable
private fun AvalancheCard(state: Section<AvalancheStatus>) {
    Card("Lawinen") {
        when (state) {
            Section.Idle, Section.Loading -> Loading()
            is Section.Failed -> {
                Hint(state.message, error = true)
                Muted("Bitte das offizielle Bulletin lesen.")
            }
            is Section.Ready -> {
                when (val s = state.value) {
                    AvalancheStatus.NoBulletin -> {
                        Text(
                            "Zurzeit gibt der SLF kein Bulletin aus.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Muted("Das heisst nicht, dass keine Gefahr besteht. Ausserhalb der Saison gibt es keine Beurteilung.")
                    }
                    is AvalancheStatus.NotMatched -> {
                        Text(
                            "Der SLF hat ein Bulletin ausgegeben, aber die App kann es diesem Gebiet noch nicht zuordnen.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Muted("Bitte das offizielle Bulletin für die Region lesen.")
                    }
                    is AvalancheStatus.Matched -> s.bulletins.take(2).forEach { BulletinView(it) }
                }
                Hint(ageText(state.fetchedAtMs, state.fromCache))
            }
        }
        LinkButton("Offizielles Bulletin (SLF)", ResortProfiles.BULLETIN_URL)
        Hint(
            "Das Bulletin ersetzt keine eigene Beurteilung vor Ort. PWDR ist keine Lawinenwarn-App und gibt keine " +
                "Empfehlung für Touren. Quelle: SLF.",
        )
    }
}

@Composable
private fun BulletinView(b: AvalancheBulletin) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(56.dp).background(dangerColor(b.maxLevel), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    b.maxLevel.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (b.maxLevel >= 4) Color.White else Color.Black,
                )
            }
            Column {
                Text(
                    "Stufe ${b.maxLevel}, ${AvalancheApi.dangerName(b.maxLevel)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Muted(b.regionNames.joinToString(", "))
            }
        }
        b.dangerLines.forEach { d ->
            Body("Stufe ${d.level} (${AvalancheApi.dangerName(d.level)}), ${d.elevationText}" +
                if (d.aspects.isNotEmpty()) ", Expositionen ${d.aspects.joinToString(" ")}" else "")
        }
        b.highlights?.let { Body(it) }
        b.problems.forEach { p ->
            Text(
                "${p.type}, ${p.elevationText}" + if (p.aspects.isNotEmpty()) ", ${p.aspects.joinToString(" ")}" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            p.comment?.let { Muted(it) }
        }
        val until = b.validUntilMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
        if (until != null) {
            Hint("Gültig bis ${until.dayOfWeek.getDisplayName(TextStyle.SHORT, DE)} ${until.format(CLOCK)} Uhr")
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Weniger" else "Ausführlich") }
        if (expanded) {
            listOf("Lawinensituation" to b.activity, "Schneedecke" to b.snowpack, "Wetter" to b.weather, "Tendenz" to b.tendency)
                .forEach { (title, text) ->
                    if (text != null) {
                        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Muted(text)
                    }
                }
        }
    }
}

// ------------------------------------------------------------------ Wetter

@Composable
private fun WeatherCard(state: Section<WeatherReport>) {
    Card("Wetter") {
        when (state) {
            Section.Idle, Section.Loading -> Loading()
            is Section.Failed -> Hint(state.message, error = true)
            is Section.Ready -> {
                val r = state.value
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SiteNow("Berg", r.mountain, Modifier.weight(1f))
                    r.valley?.let { SiteNow("Tal", it, Modifier.weight(1f)) }
                }
                val zero = r.mountain.hours.firstOrNull {
                    !it.time.isBefore(LocalDateTime.now().withMinute(0).withSecond(0).withNano(0))
                }?.freezingLevelM
                if (zero != null) Muted("Nullgradgrenze ${Math.round(zero / 10.0) * 10} m")

                val hours = WeatherApi.upcoming(r.mountain.hours, LocalDateTime.now())
                if (hours.isNotEmpty()) {
                    Text("Nächste Stunden (Berg)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(hours) { h ->
                            Column(
                                Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .width(64.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("${h.time.hour} Uhr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(temp(h.tempC), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                val snowOrRain = if ((h.snowCm ?: 0.0) > 0.05) String.format(DE, "%.1f cm", h.snowCm)
                                else if ((h.precipMm ?: 0.0) > 0.05) String.format(DE, "%.1f mm", h.precipMm) else "–"
                                Text(snowOrRain, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                Text("Nächste Tage (Berg)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.mountain.days.forEach { d ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, DE),
                            Modifier.width(36.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Column(Modifier.weight(1f)) {
                            Body(WeatherApi.describe(d.code))
                            val extras = listOfNotNull(
                                d.snowSumCm?.takeIf { it >= 0.5 }?.let { String.format(DE, "Schnee %.0f cm", it) },
                                d.gustMaxKmh?.let { "Böen ${Math.round(it)} km/h" },
                            )
                            if (extras.isNotEmpty()) Hint(extras.joinToString(" · "))
                        }
                        Text(
                            "${temp(d.tMaxC)} / ${temp(d.tMinC)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Hint(ageText(state.fetchedAtMs, state.fromCache))
            }
        }
        Hint("Wetterdaten: Open-Meteo.com (CC BY 4.0), Modell MeteoSchweiz.")
    }
}

/** Ganzzahlig gerundet, ohne "-0". */
private fun temp(v: Double?) = if (v == null) "–" else "${Math.round(v)} °C"

@Composable
private fun SiteNow(label: String, site: WeatherSite, modifier: Modifier) {
    val n = site.now
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("$label ${site.elevationM} m", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(temp(n?.tempC), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Body(WeatherApi.describe(n?.code))
        if (n?.windKmh != null) {
            Hint("Wind ${Math.round(n.windKmh)} km/h" + (n.gustKmh?.let { ", Böen ${Math.round(it)}" } ?: ""))
        }
        n?.cloudPct?.let { Hint("Bewölkung $it %") }
    }
}

// ------------------------------------------------------------------ Schnee

@Composable
private fun SnowCard(state: Section<List<SnowStationReport>>, weather: Section<WeatherReport>) {
    Card("Schnee") {
        when (state) {
            Section.Idle, Section.Loading -> Loading()
            is Section.Failed -> Hint(state.message, error = true)
            is Section.Ready -> {
                state.value.forEach { StationRow(it) }
                Hint(ageText(state.fetchedAtMs, state.fromCache))
            }
        }
        // Prognose klar getrennt von der Messung
        (weather as? Section.Ready)?.value?.mountain?.days?.takeIf { d -> d.any { (it.snowSumCm ?: 0.0) >= 0.5 } }?.let { days ->
            Text("Prognose Neuschnee (Modell, keine Messung)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Body(days.joinToString(" · ") {
                "${it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, DE)} ${String.format(DE, "%.0f", it.snowSumCm ?: 0.0)} cm"
            })
        }
        Hint(
            "Automatische Schneehöhenmessungen können Fehler enthalten, etwa bei starkem Schneefall. " +
                "Messwerte: SLF IMIS-Messnetz.",
        )
        Hint("Im Tal gibt es nur wenige Messstationen. Eine Messung im Tal wird nicht geschätzt.")
    }
}

@Composable
private fun StationRow(r: SnowStationReport) {
    val latest = r.latest
    val time = latest?.let { Instant.ofEpochMilli(it.timeMs).atZone(ZoneId.systemDefault()).format(CLOCK) }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${r.ref.label}, ${r.ref.elevationM} m", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Hint("${String.format(DE, "%.1f", r.ref.distanceKm)} km entfernt" + (time?.let { ", Messung $it Uhr" } ?: ""))
            }
            Text(
                latest?.hsCm?.let { "${Math.round(it)} cm" } ?: "–",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val newSnow = r.newSnow24hCm
        Muted(
            when {
                newSnow == null -> "Neuschnee 24 h: nicht beurteilbar"
                newSnow <= 0.0 -> "Neuschnee 24 h: keiner"
                else -> "Neuschnee 24 h: +${Math.round(newSnow)} cm"
            } + (latest?.tempC?.let { ", Lufttemperatur ${Math.round(it)} °C" } ?: ""),
        )
        val series = r.samples.mapNotNull { s -> s.hsCm?.let { s.timeMs to it } }
        if (series.size >= 4) SnowCurve(series)
    }
}

/** Kleine Kurve der Schneehöhe der letzten 24 Stunden. */
@Composable
private fun SnowCurve(series: List<Pair<Long, Double>>) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.surface
    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        val minV = series.minOf { it.second }
        val maxV = series.maxOf { it.second }
        val span = (maxV - minV).coerceAtLeast(5.0)
        val t0 = series.first().first
        val tSpan = (series.last().first - t0).coerceAtLeast(1)
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        series.zipWithNext().forEach { (a, b) ->
            fun x(t: Long) = (t - t0).toFloat() / tSpan * size.width
            fun y(v: Double) = size.height - ((v - minV) / span * (size.height - 6.dp.toPx())).toFloat() - 3.dp.toPx()
            drawLine(line, Offset(x(a.first), y(a.second)), Offset(x(b.first), y(b.second)), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

// ------------------------------------------------------------------ Webcams und Quellen

@Composable
private fun WebcamCard(resort: ResortProfile) {
    Card("Webcams") {
        Muted("Wie sieht es oben aus? Die Bilder öffnen sich auf der Seite der Betreiber.")
        resort.webcams.forEach { WebcamRow(it) }
        LinkButton("Webseite ${resort.name}", resort.websiteUrl)
    }
}

@Composable
private fun WebcamRow(w: Webcam) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .clickable { openUrl(context, w.url) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(w.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Hint("Quelle: ${w.source} · Link geprüft ${w.lastChecked}")
        }
        Text("Öffnen", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SourcesCard() {
    Card("Quellen") {
        Hint("Lawinenbulletin und Schneemessungen: SLF (CC BY 4.0). Wetter: MeteoSchweiz über Open-Meteo.com (CC BY 4.0).")
        Hint("Karten: swisstopo. Pisten und Lifte: OpenStreetMap-Mitwirkende (ODbL). Webcams gehören den Betreibern und werden nur verlinkt.")
    }
}
