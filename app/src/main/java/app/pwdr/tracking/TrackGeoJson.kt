package app.pwdr.tracking

import java.util.Locale

/** Rechteck um eine Spur, für den Kartenausschnitt. */
data class TrackBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/**
 * Baut die Spur als GeoJSON für die Karte: ein kurzes Linienstück je Punktepaar mit den Eigenschaften
 * `speed` (km/h) und `activity` (DOWNHILL, LIFT). Pausen werden nicht gezeichnet. Die Karte färbt
 * Abfahrten nach `speed` und Lifte grau.
 */
object TrackGeoJson {
    /** Zwischen zwei Punkten mit mehr Abstand (Signalverlust, Pause zwischen Aufzeichnungen) wird nicht verbunden. */
    const val MAX_GAP_MS = 30_000L

    /** Mindestlänge eines Linienstücks in Metern. Kürzere Stücke verwirft MapLibre bei kleinem Zoom. */
    const val MIN_CHUNK_M = 30.0

    fun build(
        sessions: List<List<Sample>>,
        ranges: List<ActivityRange>,
        maxAccuracyM: Float = 30f,
    ): String {
        val sorted = ranges.sortedBy { it.startMs }
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true

        // Aktuelles Stück: mehrere Punkte, damit MapLibre es bei kleinem Zoom nicht als zu kurz verwirft.
        val chunk = ArrayList<Sample>()
        var chunkActivity = Activity.PAUSE
        var chunkLen = 0.0

        fun flush() {
            if (chunk.size >= 2) {
                if (!first) sb.append(',')
                first = false
                val speed = chunk.drop(1).map { it.speedMs }.average() * 3.6
                sb.append("""{"type":"Feature","properties":{"speed":""")
                    .append(String.format(Locale.ROOT, "%.1f", speed))
                    .append(""","activity":"${chunkActivity.name}"},"geometry":{"type":"LineString","coordinates":[""")
                chunk.forEachIndexed { i, c ->
                    if (i > 0) sb.append(',')
                    sb.append(String.format(Locale.ROOT, "[%.6f,%.6f]", c.lon, c.lat))
                }
                sb.append("]}}")
            }
            val last = chunk.lastOrNull()
            chunk.clear()
            chunkLen = 0.0
            // Das nächste Stück beginnt am Ende des vorigen, damit die Linie ohne Lücke weiterläuft.
            if (last != null) chunk.add(last)
        }

        var idx = 0
        for (session in sessions) {
            chunk.clear()
            chunkLen = 0.0
            var prev: Sample? = null
            for (s in session) {
                if (s.accuracyM > maxAccuracyM) continue
                while (idx > 0 && sorted[idx].startMs > s.timeMs) idx--
                while (idx < sorted.size - 1 && sorted[idx].endMs < s.timeMs) idx++
                val r = sorted.getOrNull(idx)
                val activity = if (r != null && s.timeMs in r.startMs..r.endMs) r.activity else Activity.PAUSE
                val p = prev
                prev = s
                val breaks = p == null || activity == Activity.PAUSE || s.timeMs - p.timeMs > MAX_GAP_MS
                if (breaks || activity != chunkActivity) {
                    // Linie unterbrechen: Rest ausgeben und neu beginnen (ohne Verbindung zum Vorgänger).
                    flush()
                    chunk.clear()
                    chunkLen = 0.0
                    if (activity == Activity.PAUSE) continue
                    chunkActivity = activity
                    // Beginn am vorigen Punkt, wenn derselbe Abschnitt weiterläuft (Aktivitätswechsel ohne Lücke).
                    if (p != null && !(s.timeMs - p.timeMs > MAX_GAP_MS) && activity != Activity.PAUSE && breaks.not()) chunk.add(p)
                    else chunk.add(s)
                    if (chunk.size == 1 && chunk[0] === s) continue
                }
                val last = chunk.last()
                chunkLen += haversineM(last.lat, last.lon, s.lat, s.lon)
                chunk.add(s)
                if (chunkLen >= MIN_CHUNK_M) flush()
            }
            flush()
        }
        return sb.append("]}").toString()
    }

    /** Rahmen der brauchbaren Punkte oder null, wenn keine vorhanden sind. */
    fun bounds(sessions: List<List<Sample>>, maxAccuracyM: Float = 30f): TrackBounds? {
        val pts = sessions.flatten().filter { it.accuracyM <= maxAccuracyM }
        if (pts.isEmpty()) return null
        return TrackBounds(
            pts.minOf { it.lat }, pts.minOf { it.lon }, pts.maxOf { it.lat }, pts.maxOf { it.lon },
        )
    }
}
