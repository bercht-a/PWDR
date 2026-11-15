package app.pwdr.tracking

import app.pwdr.osm.OsmWay
import app.pwdr.osm.WayKind
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Ergebnis des Map-Matchings: Name ist null, wenn die Linie in OSM keinen Namen hat. */
data class MatchResult(val wayId: Long, val name: String?, val difficulty: String?, val subtype: String?)

/**
 * Ordnet einen Abschnitt (Abfahrt oder Lift) der nächsten OSM-Piste bzw. dem nächsten OSM-Lift zu.
 * Jeder Messpunkt stimmt für die nächste Linie innerhalb der Toleranz, die Linie mit den meisten
 * Stimmen gewinnt (Mehrheitsentscheid, siehe PLAN.md Abschnitt 4). Linien mit gleichem Namen werden
 * zusammengezählt, weil OSM eine Piste oft in mehrere Wege teilt. Läuft komplett offline.
 */
object RunMatcher {
    const val PISTE_TOLERANCE_M = 35.0
    const val LIFT_TOLERANCE_M = 30.0
    /** Mindestanteil der Punkte, die zur Gewinnerlinie passen müssen. */
    const val MIN_SHARE = 0.4

    fun match(samples: List<Sample>, ways: List<OsmWay>, kind: WayKind): MatchResult? {
        val usable = samples.filter { it.accuracyM <= 30f }
        if (usable.size < 3) return null
        val tolerance = if (kind == WayKind.PISTE) PISTE_TOLERANCE_M else LIFT_TOLERANCE_M
        val candidates = ways.filter { it.kind == kind }
        if (candidates.isEmpty()) return null

        val refLat = usable.first().lat
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat))

        // Linien in lokale Meter projizieren und nach grobem Rahmen vorfiltern.
        val minLat = usable.minOf { it.lat }; val maxLat = usable.maxOf { it.lat }
        val minLon = usable.minOf { it.lon }; val maxLon = usable.maxOf { it.lon }
        val padLat = tolerance / mPerDegLat
        val padLon = tolerance / mPerDegLon
        class Line(val way: OsmWay, val xs: DoubleArray, val ys: DoubleArray)
        val lines = candidates.filter { w ->
            boxOverlap(w, minLat - padLat, maxLat + padLat, minLon - padLon, maxLon + padLon)
        }.map { w ->
            Line(
                w,
                DoubleArray(w.points.size) { (w.points[it].lon - usable.first().lon) * mPerDegLon },
                DoubleArray(w.points.size) { (w.points[it].lat - refLat) * mPerDegLat },
            )
        }
        if (lines.isEmpty()) return null

        val votes = HashMap<String, Int>()
        val representative = HashMap<String, OsmWay>()
        var counted = 0
        for (s in usable) {
            val x = (s.lon - usable.first().lon) * mPerDegLon
            val y = (s.lat - refLat) * mPerDegLat
            var bestD = Double.MAX_VALUE
            var best: Line? = null
            for (l in lines) {
                val d = distanceToLine(x, y, l.xs, l.ys)
                if (d < bestD) {
                    bestD = d
                    best = l
                }
            }
            counted++
            if (best != null && bestD <= tolerance) {
                val key = groupKey(best.way)
                votes[key] = (votes[key] ?: 0) + 1
                // Repräsentant der Gruppe liefert Schwierigkeit und Typ: der zuerst getroffene Weg.
                representative.getOrPut(key) { best.way }
            }
        }
        val winnerKey = votes.maxByOrNull { it.value } ?: return null
        if (winnerKey.value.toDouble() / counted < MIN_SHARE) return null
        val way = representative.getValue(winnerKey.key)
        return MatchResult(way.id, way.name, way.difficulty, way.subtype)
    }

    /** Gleicher Name = gleiche Piste. Ohne Namen zählt jeder Weg für sich. */
    private fun groupKey(w: OsmWay) = w.name?.let { "n:$it" } ?: "id:${w.id}"

    private fun boxOverlap(w: OsmWay, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Boolean {
        val wMinLat = w.points.minOf { it.lat }; val wMaxLat = w.points.maxOf { it.lat }
        val wMinLon = w.points.minOf { it.lon }; val wMaxLon = w.points.maxOf { it.lon }
        return wMinLat <= maxLat && wMaxLat >= minLat && wMinLon <= maxLon && wMaxLon >= minLon
    }

    private fun distanceToLine(px: Double, py: Double, xs: DoubleArray, ys: DoubleArray): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until xs.size - 1) {
            best = min(best, distanceToSegment(px, py, xs[i], ys[i], xs[i + 1], ys[i + 1]))
        }
        return best
    }

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len2))
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
