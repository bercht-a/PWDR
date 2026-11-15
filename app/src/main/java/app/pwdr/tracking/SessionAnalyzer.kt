package app.pwdr.tracking

/**
 * Wertet eine komplette Aufzeichnung nachträglich aus: Abschnitte erkennen, kurze Lift-Pausen
 * zusammenführen, Rauschen entfernen. Läuft nach dem Stopp und bei der Wiederherstellung nach
 * einem Absturz auf den gespeicherten Rohpunkten.
 */
object SessionAnalyzer {

    /** Abfahrten mit weniger Höhenmetern gelten als Rauschen (z. B. kurzes Rutschen) und werden zur Pause. */
    private const val MIN_RUN_VERT_DOWN_M = 10.0

    fun analyze(samples: List<Sample>): List<Segment> {
        if (samples.isEmpty()) return emptyList()
        val detector = ActivityDetector()
        val raw = mutableListOf<Segment>()
        for (s in samples) raw.addAll(detector.onSample(s).closed)
        raw.addAll(detector.finish())

        val merged = SegmentStatsCalculator.mergeShortLiftPauses(raw)
        return dropNoise(merged)
    }

    private fun dropNoise(segments: List<Segment>): List<Segment> {
        val relabeled = segments.map { seg ->
            if (seg.activity == Activity.DOWNHILL &&
                SegmentStatsCalculator.compute(seg.samples).vertDownM < MIN_RUN_VERT_DOWN_M
            ) {
                Segment(Activity.PAUSE, seg.samples)
            } else {
                seg
            }
        }
        // Benachbarte Pausen zusammenfassen.
        val out = mutableListOf<Segment>()
        for (seg in relabeled) {
            val last = out.lastOrNull()
            if (last != null && last.activity == Activity.PAUSE && seg.activity == Activity.PAUSE) {
                out[out.lastIndex] = Segment(Activity.PAUSE, last.samples + seg.samples)
            } else {
                out.add(seg)
            }
        }
        return out
    }
}
