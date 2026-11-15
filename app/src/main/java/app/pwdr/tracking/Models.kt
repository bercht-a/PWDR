package app.pwdr.tracking

/** Ein GPS-Messpunkt. Geschwindigkeit in m/s, Höhe in m, Genauigkeit in m. */
data class Sample(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val speedMs: Double,
    val accuracyM: Float = 5f,
    /** Rohe GPS-Höhe, falls altM vom Barometer stammt (nur zur Diagnose und für den GPX-Export). */
    val gpsAltM: Double? = null,
)

enum class Activity { DOWNHILL, LIFT, PAUSE }

/** Ein zusammenhängender Abschnitt gleicher Aktivität. */
data class Segment(
    val activity: Activity,
    val samples: List<Sample>,
) {
    val startMs: Long get() = samples.first().timeMs
    val endMs: Long get() = samples.last().timeMs
    val durationMs: Long get() = endMs - startMs
}

/** Kennzahlen eines Abschnitts. */
data class SegmentStats(
    val distanceM: Double,
    val vertDownM: Double,
    val vertUpM: Double,
    val maxSpeedMs: Double,
    val avgSpeedMs: Double,
    val durationMs: Long,
)
