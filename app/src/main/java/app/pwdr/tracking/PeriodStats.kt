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

    /**
     * Erster und letzter Tag des Zeitraums, [offset] Zeiträume zurück (0 = aktueller, -1 = Vorwoche, Vormonat, Vorsaison).
     * Der aktuelle Zeitraum endet heute, frühere enden mit ihrem letzten Tag.
     */
    fun range(today: LocalDate, offset: Int): Pair<LocalDate, LocalDate> {
        val cur = start(today)
        val from = when (this) {
            WEEK -> cur.plusWeeks(offset.toLong())
            MONTH -> cur.plusMonths(offset.toLong())
            SEASON -> cur.plusYears(offset.toLong())
        }
        val last = when (this) {
            WEEK -> from.plusDays(6)
            MONTH -> from.plusMonths(1).minusDays(1)
            SEASON -> from.plusYears(1).minusDays(1)
        }
        return from to minOf(last, today)
    }

    /** Beschriftung des Zeitraums für die Kopfzeile, zum Beispiel "9.–15. Nov 2026", "November 2026" oder "Saison 2026/27". */
    fun title(today: LocalDate, offset: Int): String {
        val (from, to) = range(today, offset)
        val m = listOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez")
        val months = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
        return when (this) {
            WEEK -> if (from.month == to.month) "${from.dayOfMonth}.–${to.dayOfMonth}. ${m[to.monthValue - 1]} ${to.year}"
            else "${from.dayOfMonth}. ${m[from.monthValue - 1]} – ${to.dayOfMonth}. ${m[to.monthValue - 1]} ${to.year}"
            MONTH -> "${months[from.monthValue - 1]} ${from.year}"
            SEASON -> "Saison ${from.year}/${(from.year + 1) % 100}"
        }
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

    fun summarize(runs: List<RunEntity>, period: Period, today: LocalDate, zone: ZoneId, offset: Int = 0): PeriodSummary {
        val (from, to) = period.range(today, offset)
        val downs = runs.filter {
            val d = java.time.Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()
            it.type == Activity.DOWNHILL.name && it.vertDownM >= MIN_RUN_VERT_DOWN_M && !d.isBefore(from) && !d.isAfter(to)
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

    /** Ein Balken der Verlaufsgrafik: Beschriftung und Höhenmeter bergab. */
    data class Bar(
        val label: String,
        val vertDownM: Double,
        val runs: Int,
        val distanceM: Double = 0.0,
        val maxSpeedMs: Double = 0.0,
        val maxG: Double? = null,
    )

    /**
     * Balken für die Grafik: Woche und Monat je Tag, Saison je Kalendermonat (Oktober bis heute). Leere Zeitabschnitte
     * erscheinen als Balken mit 0, damit die Achse vollständig bleibt. Tage nach [today] fehlen.
     */
    fun bars(runs: List<RunEntity>, period: Period, today: LocalDate, zone: ZoneId, offset: Int = 0): List<Bar> {
        val (from, to) = period.range(today, offset)
        val downs = runs.filter { it.type == Activity.DOWNHILL.name && it.vertDownM >= MIN_RUN_VERT_DOWN_M }
            .map { java.time.Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() to it }
            .filter { !it.first.isBefore(from) && !it.first.isAfter(to) }
        return when (period) {
            Period.SEASON -> {
                val out = ArrayList<Bar>()
                var m = java.time.YearMonth.from(from)
                val last = java.time.YearMonth.from(to)
                while (!m.isAfter(last)) {
                    val inMonth = downs.filter { java.time.YearMonth.from(it.first) == m }
                    out += bar(MONTHS[m.monthValue - 1], inMonth.map { it.second })
                    m = m.plusMonths(1)
                }
                out
            }
            else -> {
                val out = ArrayList<Bar>()
                var d = from
                while (!d.isAfter(to)) {
                    val day = downs.filter { it.first == d }
                    val label = if (period == Period.WEEK) WEEKDAYS[d.dayOfWeek.value - 1] else d.dayOfMonth.toString()
                    out += bar(label, day.map { it.second })
                    d = d.plusDays(1)
                }
                out
            }
        }
    }

    private fun bar(label: String, rs: List<RunEntity>) = Bar(
        label, rs.sumOf { it.vertDownM }, rs.size, rs.sumOf { it.distanceM },
        rs.maxOfOrNull { it.maxSpeedMs } ?: 0.0, rs.mapNotNull { it.maxG }.maxOrNull(),
    )

    private val MONTHS = listOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez")
    private val WEEKDAYS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
}
