package app.pwdr.tracking

/** Ein Punkt der Tagesgrafik. [cumDistM] zählt nur Abfahrten (Pistendistanz). */
data class ProfilePoint(
    val timeMs: Long,
    val altM: Double,
    val speedMs: Double,
    val cumDistM: Double,
    val activity: Activity,
)

/** Zeitbereich eines erkannten Abschnitts, Eingabe für [DayProfile.build]. */
data class ActivityRange(val activity: Activity, val startMs: Long, val endMs: Long)

object DayProfile {
    /** Zwischen zwei Punkten mit mehr Abstand wird die Linie unterbrochen (Mittagspause, Pause zwischen Aufzeichnungen). */
    const val GAP_MS = 5 * 60_000L

    /**
     * Baut die ausgedünnten Profilpunkte (ca. alle [stepMs]) aus den Rohpunkten mehrerer Aufzeichnungen.
     * Die kumulierte Distanz wird über alle Rohpunkte gerechnet, bevor ausgedünnt wird, damit sie nicht
     * durch das Ausdünnen schrumpft. Ungenaue Punkte (> [maxAccuracyM]) zählen nicht zur Distanz.
     */
    fun build(
        sessions: List<List<Sample>>,
        ranges: List<ActivityRange>,
        stepMs: Long = 5_000L,
        maxAccuracyM: Float = 30f,
    ): List<ProfilePoint> {
        val sortedRanges = ranges.sortedBy { it.startMs }
        val out = ArrayList<ProfilePoint>()
        var cum = 0.0
        var rangeIdx = 0
        for (session in sessions) {
            var prev: Sample? = null
            var lastKept: Long? = null
            for (s in session) {
                if (s.accuracyM > maxAccuracyM) continue
                while (rangeIdx > 0 && sortedRanges[rangeIdx].startMs > s.timeMs) rangeIdx--
                while (rangeIdx < sortedRanges.size - 1 && sortedRanges[rangeIdx].endMs < s.timeMs) rangeIdx++
                val r = sortedRanges.getOrNull(rangeIdx)
                val activity = if (r != null && s.timeMs in r.startMs..r.endMs) r.activity else Activity.PAUSE
                val p = prev
                if (p != null && activity == Activity.DOWNHILL) {
                    cum += haversineM(p.lat, p.lon, s.lat, s.lon)
                }
                prev = s
                if (lastKept == null || s.timeMs - lastKept >= stepMs) {
                    out.add(ProfilePoint(s.timeMs, s.altM, s.speedMs, cum, activity))
                    lastKept = s.timeMs
                }
            }
        }
        return out
    }
}
