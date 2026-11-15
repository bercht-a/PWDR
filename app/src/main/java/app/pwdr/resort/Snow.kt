package app.pwdr.resort

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

/** Ein Messwert einer IMIS-Station (alle 30 Minuten). Schneehöhe in cm. Quelle: SLF. */
data class ImisSample(val timeMs: Long, val hsCm: Double?, val tempC: Double?, val windMs: Double?)

@Serializable
private data class ImisRow(
    @SerialName("measure_date") val measureDate: String,
    @SerialName("HS") val hs: Double? = null,
    @SerialName("TA_30MIN_MEAN") val temp: Double? = null,
    @SerialName("VW_30MIN_MEAN") val wind: Double? = null,
)

/** Auswertung einer Station: aktueller Stand, Kurve der letzten 24 Stunden und Neuschnee. */
data class SnowStationReport(
    val ref: SnowStationRef,
    val samples: List<ImisSample>,
    val latest: ImisSample?,
    /** Neuschnee der letzten 24 h in cm, null wenn die Daten dafür nicht reichen oder auffällig sind. */
    val newSnow24hCm: Double?,
)

object SnowApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun url(code: String) = "https://measurement-api.slf.ch/public/api/imis/station/$code/measurements"

    fun parse(body: String): List<ImisSample> =
        json.decodeFromString<List<ImisRow>>(body).mapNotNull { r ->
            val t = runCatching { OffsetDateTime.parse(r.measureDate).toInstant().toEpochMilli() }.getOrNull()
                ?: return@mapNotNull null
            ImisSample(t, r.hs, r.temp, r.wind)
        }.sortedBy { it.timeMs }

    fun report(ref: SnowStationRef, samples: List<ImisSample>): SnowStationReport =
        SnowStationReport(ref, samples, samples.lastOrNull { it.hsCm != null } ?: samples.lastOrNull(), SnowMath.newSnow24h(samples))
}

object SnowMath {
    /** Ein Sprung der Schneehöhe von mehr als diesem Wert zwischen zwei Messungen gilt als Messfehler (PLAN.md 5d). */
    const val MAX_STEP_CM = 30.0

    /** Mindestens so viele Stunden müssen die Daten abdecken, sonst kein Neuschnee-Wert. */
    const val MIN_SPAN_H = 20.0

    /**
     * Neuschnee der letzten 24 h als Änderung der Schneehöhe. Ein Median über drei Werte glättet einzelne
     * Ausreißer. Bei Sprüngen über [MAX_STEP_CM] oder zu kurzer Datenreihe gibt es keinen Wert (null).
     * Sinkt die Schneehöhe (Setzung, Schmelze), ist der Neuschnee 0, nicht negativ.
     */
    fun newSnow24h(samples: List<ImisSample>): Double? {
        val pts = samples.filter { it.hsCm != null }.sortedBy { it.timeMs }
        if (pts.size < 6) return null
        val hs = pts.map { it.hsCm!! }
        if (hs.zipWithNext().any { (a, b) -> kotlin.math.abs(b - a) > MAX_STEP_CM }) return null
        val spanH = (pts.last().timeMs - pts.first().timeMs) / 3_600_000.0
        if (spanH < MIN_SPAN_H) return null
        val smooth = hs.indices.map { i ->
            val window = hs.subList(maxOf(0, i - 1), minOf(hs.size, i + 2)).sorted()
            window[window.size / 2]
        }
        val diff = smooth.last() - smooth.first()
        return if (diff < 1.0) 0.0 else Math.round(diff).toDouble()
    }
}
