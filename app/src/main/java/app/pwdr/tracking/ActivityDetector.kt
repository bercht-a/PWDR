package app.pwdr.tracking

/**
 * Erkennt aus einem Strom von GPS-Punkten, ob man gerade abfährt, im Lift sitzt oder pausiert.
 *
 * Reine Logik ohne Android-Abhängigkeit, damit sie mit simulierten Strecken getestet werden kann.
 *
 * Idee (siehe PLAN.md, Abschnitt 4):
 * - Die Höhe wird geglättet, aus einem kurzen Zeitfenster entsteht die Vertikalgeschwindigkeit.
 * - Pro Punkt gibt es einen Kandidaten (Lift, Abfahrt, Pause). Ein Wechsel gilt erst, wenn der
 *   Kandidat eine Mindestdauer anhält (Hysterese gegen Flackern). Die Punkte seit Beginn des
 *   Kandidaten gehören dann rückwirkend zum neuen Abschnitt.
 * - Fährt man flach weiter (Querung), bleibt der aktuelle Zustand erhalten.
 */
class ActivityDetector(private val config: Config = Config()) {

    data class Config(
        val windowMs: Long = 8_000,
        /** Unter dieser Geschwindigkeit (m/s) gilt man als stehend. */
        val pauseSpeedMs: Double = 0.8,
        /** Steigen schneller als dieser Wert (m/s) bedeutet Lift. */
        val liftVz: Double = 0.25,
        /** Sinken schneller als dieser Wert (m/s, negativ) bedeutet Abfahrt. */
        val downVz: Double = -0.35,
        val confirmDownhillMs: Long = 5_000,
        val confirmLiftMs: Long = 10_000,
        val confirmPauseMs: Long = 20_000,
        /** Punkte mit schlechterer Genauigkeit (m) werden ignoriert. */
        val maxAccuracyM: Float = 30f,
        /** Glättung der Höhe (0..1, kleiner ist glatter). */
        val altAlpha: Double = 0.3,
    )

    data class Update(
        val activity: Activity,
        /** Abschnitte, die mit diesem Punkt abgeschlossen wurden. */
        val closed: List<Segment>,
    )

    private class Entry(val sample: Sample, val smoothAlt: Double)

    private val window = ArrayDeque<Entry>()
    private var smoothAlt: Double? = null

    private var current: Activity = Activity.PAUSE
    private var currentSamples = mutableListOf<Sample>()

    private var candidate: Activity? = null
    private var candidateStartMs: Long = 0
    private var pending = mutableListOf<Sample>()

    val activity: Activity get() = current

    /** Punkte des laufenden Abschnitts inklusive noch nicht bestätigter Punkte. */
    val currentSegmentSamples: List<Sample> get() = currentSamples + pending

    fun onSample(s: Sample): Update {
        if (s.accuracyM > config.maxAccuracyM) return Update(current, emptyList())

        val alt = smoothAlt?.let { it + config.altAlpha * (s.altM - it) } ?: s.altM
        smoothAlt = alt
        window.addLast(Entry(s, alt))
        while (window.size > 1 && s.timeMs - window.first().sample.timeMs > config.windowMs) {
            window.removeFirst()
        }

        val cand = classify()
        val closed = mutableListOf<Segment>()

        if (cand == null || cand == current) {
            // Kein Wechsel: wartende Punkte gehören zum aktuellen Abschnitt.
            currentSamples.addAll(pending)
            pending.clear()
            candidate = null
            currentSamples.add(s)
        } else {
            if (candidate != cand) {
                // Neuer Kandidat: bisher wartende Punkte gehören zum aktuellen Abschnitt.
                currentSamples.addAll(pending)
                pending.clear()
                candidate = cand
                candidateStartMs = s.timeMs
            }
            pending.add(s)
            if (s.timeMs - candidateStartMs >= confirmMs(cand)) {
                if (currentSamples.isNotEmpty()) closed.add(Segment(current, currentSamples.toList()))
                current = cand
                currentSamples = pending.toMutableList()
                pending.clear()
                candidate = null
            }
        }
        return Update(current, closed)
    }

    /** Schliesst den laufenden Abschnitt ab (z. B. beim Stoppen der Aufzeichnung). */
    fun finish(): List<Segment> {
        val all = currentSamples + pending
        currentSamples = mutableListOf()
        pending = mutableListOf()
        candidate = null
        return if (all.isEmpty()) emptyList() else listOf(Segment(current, all))
    }

    private fun confirmMs(a: Activity): Long = when (a) {
        Activity.DOWNHILL -> config.confirmDownhillMs
        Activity.LIFT -> config.confirmLiftMs
        Activity.PAUSE -> config.confirmPauseMs
    }

    /** Aktivität nach dem aktuellen Zeitfenster, oder null wenn unklar. */
    private fun classify(): Activity? {
        val first = window.first()
        val last = window.last()
        val dt = (last.sample.timeMs - first.sample.timeMs) / 1000.0
        if (dt < config.windowMs / 2000.0) return null // zu wenig Daten

        val hSpeed = window.map { it.sample.speedMs }.average()
        val vSpeed = (last.smoothAlt - first.smoothAlt) / dt

        return when {
            hSpeed < config.pauseSpeedMs -> Activity.PAUSE
            vSpeed > config.liftVz -> Activity.LIFT
            vSpeed < config.downVz -> Activity.DOWNHILL
            else -> null // flach unterwegs: Zustand beibehalten
        }
    }
}
