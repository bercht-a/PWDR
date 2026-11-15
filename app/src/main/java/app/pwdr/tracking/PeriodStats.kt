package app.pwdr.tracking

import app.pwdr.data.RunEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Zeitraum für die Zusammenfassung in der Statistik. */
enum class Period(val label: String) {
    WEEK("Woche"),
    MONTH("Monat"),
    SEASON("Saison"),
    ;

    /** Erster Tag des Zeitraums, der [today] enthält. Die Saison beginnt am 1. Oktober. */
    fun start(today: LocalDate): LocalDate = when (this) {
        WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        MONTH -> today.withDayOfMonth(1)
        SEASON -> if (today.monthValue >= 10) LocalDate.of(today.year, 10, 1) else LocalDate.of(today.year - 1, 10, 1)
    }
}

/** Summen über einen Zeitraum. [maxG] und [avgG] sind null, wenn im Zeitraum keine G-Werte vorliegen. */
data class PeriodSummary(
    val days: Int,
    val runs: Int,
    val vertDownM: Double,
    val distanceM: Double,
    val vmaxMs: Double,
    val maxG: Double?,
    /** Mittel der Durchschnitts-G aller Abfahrten mit Werten. */
    val avgG: Double?,
)

object PeriodStats {
    /** Abfahrten mit weniger Höhenmetern zählen nicht (wie in der Tagesliste). */
    const val MIN_RUN_VERT_DOWN_M = 10.0

    fun summarize(runs: List<RunEntity>, period: Period, today: LocalDate, zone: ZoneId): PeriodSummary {
        val from = period.start(today)
        val downs = runs.filter {
            it.type == Activity.DOWNHILL.name && it.vertDownM >= MIN_RUN_VERT_DOWN_M &&
                !java.time.Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate().isBefore(from)
        }
        val days = downs.map { java.time.Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }.distinct().size
        return PeriodSummary(
            days = days,
            runs = downs.size,
            vertDownM = downs.sumOf { it.vertDownM },
            distanceM = downs.sumOf { it.distanceM },
            vmaxMs = downs.maxOfOrNull { it.maxSpeedMs } ?: 0.0,
            maxG = downs.mapNotNull { it.maxG }.maxOrNull(),
            avgG = downs.mapNotNull { it.avgG }.takeIf { it.isNotEmpty() }?.average(),
        )
    }
}
