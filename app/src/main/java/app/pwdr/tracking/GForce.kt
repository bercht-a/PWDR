package app.pwdr.tracking

import kotlin.math.sqrt

/**
 * Wo das Handy getragen wird. Die Position verändert die Messwerte: in der Hosentasche schlägt das Handy mehr an und
 * rauscht stärker, am Körper vorn ist das Signal am ruhigsten, im Rucksack ist es gedämpft. Gewählt wird deshalb die
 * Glättung (Anzahl Sensorwerte, über die gemittelt wird). Die Werte bleiben ein Richtwert.
 */
enum class CarryPosition(val key: String, val label: String, val smoothing: Int, val hint: String) {
    POCKET("pocket", "Hosentasche", 7, "Stärker geglättet, weil das Handy mitschlägt"),
    CHEST("chest", "Brust oder Jacke", 5, "Ruhigstes Signal, am besten für Vergleiche"),
    BACKPACK("backpack", "Rucksack", 9, "Stark geglättet, Werte fallen eher niedrig aus"),
    ;

    companion object {
        fun of(key: String?): CarryPosition = entries.firstOrNull { it.key == key } ?: POCKET
    }
}

/** G-Werte eines Zeitfensters (zwischen zwei GPS-Punkten). 1,0 g ist die Ruhe (Erdbeschleunigung). */
data class GReading(val max: Double, val mean: Double)

/**
 * Wertet den Beschleunigungssensor aus (PLAN.md Phase 8, Grundlage). Es zählt der Betrag der gesamten Beschleunigung
 * einschliesslich Schwerkraft, geteilt durch 9,80665. Der Betrag ist unabhängig von der Lage des Handys, die Trageposition
 * (Hosentasche, Brust) verändert die Werte aber trotzdem. Deshalb gilt G-Force als Richtwert.
 *
 * Die Rohwerte werden über ein kurzes Fenster gemittelt, damit einzelne Stösse (Handy schlägt an, Sensorrauschen) nicht
 * als Höchstwert zählen. Aus den Rohwerten entsteht pro Fenster nur [GReading], gespeichert werden keine Rohdaten.
 */
class GForceTracker(private val smoothing: Int = SMOOTHING_SAMPLES) {
    private val window = ArrayDeque<Double>()
    private var windowSum = 0.0
    private var max = 0.0
    private var sum = 0.0
    private var count = 0

    /** Ein Sensorwert in m/s² (x, y, z). Nicht endliche Werte werden verworfen. */
    @Synchronized
    fun onAccel(ax: Float, ay: Float, az: Float) {
        val g = sqrt((ax * ax + ay * ay + az * az).toDouble()) / STANDARD_GRAVITY
        if (!g.isFinite()) return
        window.addLast(g)
        windowSum += g
        if (window.size > smoothing) windowSum -= window.removeFirst()
        if (window.size < smoothing) return
        val smooth = (windowSum / window.size).coerceAtMost(MAX_PLAUSIBLE_G)
        if (smooth > max) max = smooth
        sum += smooth
        count++
    }

    /** Liefert die Werte seit dem letzten Aufruf und beginnt ein neues Fenster. Null, wenn zu wenig Messwerte da sind. */
    @Synchronized
    fun drain(): GReading? {
        val reading = if (count >= MIN_SAMPLES) GReading(max, sum / count) else null
        max = 0.0
        sum = 0.0
        count = 0
        return reading
    }

    companion object {
        const val STANDARD_GRAVITY = 9.80665

        /** Werte darüber gelten als Messfehler (Sturz des Handys, Sensorfehler). */
        const val MAX_PLAUSIBLE_G = 10.0
        const val SMOOTHING_SAMPLES = 5
        const val MIN_SAMPLES = 5
    }
}

/** Kennzahlen einer Abfahrt. */
data class GStats(val max: Double, val mean: Double)

object GForceStats {
    /** Ab diesem Wert gilt eine Belastung als Kurve. Darunter (Ruhe, Gleiten) gibt es keine "stärkste Kurve". */
    const val MIN_PEAK_G = 1.15

    /** Die Stelle mit dem höchsten G-Wert. */
    data class Peak(val timeMs: Long, val lat: Double, val lon: Double, val g: Double)

    /**
     * Stärkste Kurve innerhalb der Abfahrten ([ranges] mit Aktivität DOWNHILL zählen). Ungenaue Punkte (> [maxAccuracyM])
     * zählen nicht, weil ihr Ort nicht stimmt. Null, wenn nichts über [MIN_PEAK_G] liegt.
     */
    fun peakIn(sessions: List<List<Sample>>, ranges: List<ActivityRange>, maxAccuracyM: Float = 30f): Peak? {
        val downs = ranges.filter { it.activity == Activity.DOWNHILL }
        if (downs.isEmpty()) return null
        var best: Peak? = null
        for (s in sessions.flatten()) {
            val g = s.gMax ?: continue
            if (s.accuracyM > maxAccuracyM || g < MIN_PEAK_G) continue
            if (downs.none { s.timeMs in it.startMs..it.endMs }) continue
            if (best == null || g > best.g) best = Peak(s.timeMs, s.lat, s.lon, g)
        }
        return best
    }

    /** Höchstwert und Durchschnitt über alle Punkte mit G-Wert. Null, wenn keiner einen hat (ältere Aufzeichnungen). */
    fun of(samples: List<Sample>): GStats? {
        val maxes = samples.mapNotNull { it.gMax }
        val means = samples.mapNotNull { it.gMean }
        if (maxes.isEmpty() || means.isEmpty()) return null
        return GStats(maxes.max(), means.average())
    }
}
