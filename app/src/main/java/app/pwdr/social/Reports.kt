package app.pwdr.social

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Art einer Community-Meldung (PLAN.md 5c). Ablaufzeit und Farbe müssen zu `post_report` in 0004_reports.sql passen. */
enum class ReportType(val key: String, val label: String, val color: String, val ttlHours: Int) {
    QUEUE("queue", "Hier ist viel los", "#FFB703", 2),
    PISTE("piste", "Pistenzustand", "#4CC9F0", 4),
    SNOW("snow", "Neuschnee", "#FFFFFF", 6),
    WARNING("warning", "Warnung", "#FF5A5F", 4),
    APRES("apres", "Après-Ski", "#8D6A4F", 6),
    OTHER("other", "Sonstiges", "#B8C4CE", 2),
    ;

    companion object {
        fun of(key: String?): ReportType = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** Zeile aus der Funktion list_reports(). Der Verfasser ist nie dabei. */
@Serializable
data class ReportRow(
    val id: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val text: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("up_votes") val upVotes: Int = 0,
    @SerialName("down_votes") val downVotes: Int = 0,
    @SerialName("my_vote") val myVote: Int = 0,
    val mine: Boolean = false,
)

object ReportsOnMap {
    const val MAX_TEXT = 140

    /** Meldungen, die noch gelten (die Anzeige räumt abgelaufene selbst weg, auch wenn die Liste älter ist). */
    fun active(rows: List<ReportRow>, nowMs: Long): List<ReportRow> = rows.filter {
        try {
            parseInstantMs(it.expiresAt) > nowMs
        } catch (e: Exception) {
            false
        }
    }

    /** GeoJSON mit id, type und color für die Kartenebene. */
    fun geoJson(rows: List<ReportRow>): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        rows.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            val t = ReportType.of(r.type)
            sb.append("""{"type":"Feature","properties":{"id":"${r.id.filter { it.isLetterOrDigit() || it == '-' }}","type":"${t.key}","color":"${t.color}"},""")
            sb.append("\"geometry\":{\"type\":\"Point\",\"coordinates\":")
            sb.append(String.format(Locale.ROOT, "[%.6f,%.6f]}}", r.lon, r.lat))
        }
        return sb.append("]}").toString()
    }

    /** "vor 12 min", "vor 1 h 5 min". */
    fun ageText(createdMs: Long, nowMs: Long): String {
        val min = ((nowMs - createdMs).coerceAtLeast(0) / 60_000).toInt()
        return when {
            min < 1 -> "gerade eben"
            min < 60 -> "vor $min min"
            else -> "vor ${min / 60} h ${min % 60} min"
        }
    }

    /** "noch 1 h 20 min" bis zum Ablauf. */
    fun remainingText(expiresMs: Long, nowMs: Long): String {
        val min = ((expiresMs - nowMs).coerceAtLeast(0) / 60_000).toInt()
        return when {
            min < 1 -> "läuft gleich ab"
            min < 60 -> "noch $min min"
            else -> "noch ${min / 60} h ${min % 60} min"
        }
    }

    /** Text für Fehler der Datenbankfunktionen. */
    fun errorText(raw: String): String = when {
        "too_many_reports" in raw -> "Zu viele Meldungen in der letzten Stunde. Bitte später wieder."
        "own_report" in raw -> "Die eigene Meldung lässt sich nicht bewerten."
        "report_not_found" in raw -> "Diese Meldung gibt es nicht mehr."
        "Unable to resolve host" in raw || "timeout" in raw.lowercase() || "Failed to connect" in raw ->
            "Keine Verbindung. Bitte Netz prüfen."
        else -> "Das hat nicht geklappt.\n(${raw.lineSequence().first().take(120)})"
    }
}
