package app.pwdr.resort

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

/**
 * Lawinenbulletin des SLF im CAAML-JSON-Format (Version 4 der Schnittstelle, PLAN.md 5d).
 * Das Modell folgt der offiziellen Beschreibung. Es ist mit echten Daten NICHT geprüft, weil der SLF
 * im November noch kein Bulletin ausgibt. Fehlende Felder sind überall erlaubt (leere Werte entfallen in der API).
 */
@Serializable private data class CaamlResponse(val bulletins: List<CaamlBulletin> = emptyList())

@Serializable private data class CaamlBulletin(
    val bulletinID: String? = null,
    val publicationTime: String? = null,
    val validTime: CaamlValid? = null,
    val nextUpdate: String? = null,
    val regions: List<CaamlRegion> = emptyList(),
    val dangerRatings: List<CaamlDanger> = emptyList(),
    val avalancheProblems: List<CaamlProblem> = emptyList(),
    val highlights: String? = null,
    val avalancheActivity: CaamlText? = null,
    val snowpackStructure: CaamlText? = null,
    val weatherForecast: CaamlText? = null,
    val travelAdvisory: CaamlText? = null,
    val tendency: List<CaamlText> = emptyList(),
)

@Serializable private data class CaamlValid(val startTime: String? = null, val endTime: String? = null)
@Serializable private data class CaamlRegion(val regionID: String? = null, val name: String? = null)
@Serializable private data class CaamlElevation(val lowerBound: String? = null, val upperBound: String? = null)
@Serializable private data class CaamlDanger(
    val mainValue: String? = null,
    val elevation: CaamlElevation? = null,
    val aspects: List<String> = emptyList(),
    val validTimePeriod: String? = null,
)
@Serializable private data class CaamlProblem(
    val problemType: String? = null,
    val comment: String? = null,
    val elevation: CaamlElevation? = null,
    val aspects: List<String> = emptyList(),
    val dangerRatingValue: String? = null,
)
@Serializable private data class CaamlText(val highlights: String? = null, val comment: String? = null)

/** Gefahrenstufe nach europäischem Standard: 1 gering, 2 mässig, 3 erheblich, 4 gross, 5 sehr gross. */
data class DangerLine(val level: Int, val elevationText: String, val aspects: List<String>, val period: String?)

data class AvalancheProblemInfo(val type: String, val elevationText: String, val aspects: List<String>, val comment: String?)

data class AvalancheBulletin(
    val id: String?,
    val regionNames: List<String>,
    val regionIds: List<String>,
    val maxLevel: Int,
    val dangerLines: List<DangerLine>,
    val problems: List<AvalancheProblemInfo>,
    val highlights: String?,
    val activity: String?,
    val snowpack: String?,
    val weather: String?,
    val tendency: String?,
    val travelAdvisory: String?,
    val publishedMs: Long?,
    val validFromMs: Long?,
    val validUntilMs: Long?,
)

/** Was die App zum Lawinenbulletin anzeigen darf. Ohne Bulletin steht "kein Bulletin", nie "keine Gefahr". */
sealed interface AvalancheStatus {
    /** Der SLF gibt zurzeit kein Bulletin aus (z. B. ausserhalb der Saison). */
    data object NoBulletin : AvalancheStatus

    /** Es gibt Bulletins, aber keines passt zur Region des Gebiets (Zuordnung offen). */
    data class NotMatched(val bulletinCount: Int) : AvalancheStatus

    data class Matched(val bulletins: List<AvalancheBulletin>) : AvalancheStatus
}

object AvalancheApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun url(lang: String = "de") = "https://aws.slf.ch/api/bulletin/caaml/$lang/json"

    fun parse(body: String): List<AvalancheBulletin> =
        json.decodeFromString<CaamlResponse>(body).bulletins.map { b ->
            val lines = b.dangerRatings.mapNotNull { d ->
                val level = dangerLevel(d.mainValue) ?: return@mapNotNull null
                DangerLine(level, elevationText(d.elevation?.lowerBound, d.elevation?.upperBound), d.aspects, d.validTimePeriod)
            }
            AvalancheBulletin(
                id = b.bulletinID,
                regionNames = b.regions.mapNotNull { it.name },
                regionIds = b.regions.mapNotNull { it.regionID },
                maxLevel = lines.maxOfOrNull { it.level } ?: 0,
                dangerLines = lines,
                problems = b.avalancheProblems.map {
                    AvalancheProblemInfo(problemName(it.problemType), elevationText(it.elevation?.lowerBound, it.elevation?.upperBound), it.aspects, stripHtml(it.comment))
                },
                highlights = stripHtml(b.highlights),
                activity = text(b.avalancheActivity),
                snowpack = text(b.snowpackStructure),
                weather = text(b.weatherForecast),
                tendency = b.tendency.mapNotNull { text(it) }.joinToString("\n\n").ifBlank { null },
                travelAdvisory = text(b.travelAdvisory),
                publishedMs = timeMs(b.publicationTime),
                validFromMs = timeMs(b.validTime?.startTime),
                validUntilMs = timeMs(b.validTime?.endTime),
            )
        }

    /**
     * Wählt die Bulletins, die zum Gebiet gehören: zuerst über die Regionskennung, sonst über Teile des Regionsnamens.
     * Aktuell gültige Bulletins zuerst, abgelaufene fallen weg.
     */
    fun status(bulletins: List<AvalancheBulletin>, profile: ResortProfile, nowMs: Long): AvalancheStatus {
        val current = bulletins.filter { it.validUntilMs == null || it.validUntilMs >= nowMs }
        if (current.isEmpty()) return AvalancheStatus.NoBulletin
        val matched = current.filter { b ->
            b.regionIds.any { it in profile.avalancheRegionIds } ||
                b.regionNames.any { n -> profile.avalancheRegionKeywords.any { k -> n.contains(k, ignoreCase = true) } }
        }
        return if (matched.isEmpty()) AvalancheStatus.NotMatched(current.size) else AvalancheStatus.Matched(matched)
    }

    fun dangerLevel(value: String?): Int? = when (value) {
        "low" -> 1
        "moderate" -> 2
        "considerable" -> 3
        "high" -> 4
        "very_high" -> 5
        else -> null
    }

    fun dangerName(level: Int): String = when (level) {
        1 -> "gering"
        2 -> "mässig"
        3 -> "erheblich"
        4 -> "gross"
        5 -> "sehr gross"
        else -> "unbekannt"
    }

    fun problemName(type: String?): String = when (type) {
        "new_snow" -> "Neuschnee"
        "wind_slab" -> "Triebschnee"
        "persistent_weak_layers" -> "Altschnee"
        "wet_snow" -> "Nassschnee"
        "gliding_snow" -> "Gleitschnee"
        "cornices" -> "Wechten"
        "no_distinct_avalanche_problem" -> "Kein ausgeprägtes Lawinenproblem"
        null -> "Lawinenproblem"
        else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** "oberhalb 2200 m", "unterhalb 2200 m", "2000 bis 2200 m" oder "alle Höhenlagen". Ohne Angabe: ganze Höhenlage. */
    fun elevationText(lower: String?, upper: String?): String {
        fun label(v: String) = if (v.equals("treeline", true)) "der Waldgrenze" else "$v m"
        return when {
            lower != null && upper != null -> "${lower} bis ${label(upper)}"
            lower != null -> "oberhalb ${label(lower)}"
            upper != null -> "unterhalb ${label(upper)}"
            else -> "alle Höhenlagen"
        }
    }

    private fun text(t: CaamlText?): String? =
        listOfNotNull(stripHtml(t?.highlights), stripHtml(t?.comment)).joinToString("\n\n").ifBlank { null }

    /** Entfernt einfache HTML-Auszeichnung aus den Texten des Bulletins. */
    fun stripHtml(s: String?): String? =
        s?.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")?.replace(Regex("<[^>]+>"), "")
            ?.replace("&nbsp;", " ")?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")
            ?.lines()?.joinToString("\n") { it.trim() }?.replace(Regex("\n{3,}"), "\n\n")?.trim()?.ifBlank { null }

    private fun timeMs(t: String?): Long? =
        t?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
}
