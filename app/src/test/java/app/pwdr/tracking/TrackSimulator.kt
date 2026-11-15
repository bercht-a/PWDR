package app.pwdr.tracking

import kotlin.math.cos
import kotlin.random.Random

/**
 * Erzeugt künstliche GPS-Strecken (1 Hz) mit Rauschen, um die Erkennung ohne Gerät zu testen.
 * Die Strecke läuft entlang eines Breitengrads Richtung Osten.
 */
class TrackSimulator(
    private val seed: Int = 42,
    private val altNoiseM: Double = 2.5,
    private val speedNoiseMs: Double = 0.3,
    startLat: Double = 46.49,
    startLon: Double = 7.56,
    startAlt: Double = 1350.0,
    startTimeMs: Long = 1_700_000_000_000,
) {
    private val rnd = Random(seed)
    private var lat = startLat
    private var lon = startLon
    private var alt = startAlt
    private var t = startTimeMs
    val samples = mutableListOf<Sample>()

    /** Schritt in Metern nach Osten bei [startLat]. */
    private fun metersToLon(m: Double) = m / (111_320.0 * cos(Math.toRadians(lat)))

    /**
     * Eine Phase: [seconds] lang mit horizontaler Geschwindigkeit [hSpeed] (m/s)
     * und Vertikalgeschwindigkeit [vSpeed] (m/s, negativ = abwärts).
     */
    fun phase(seconds: Int, hSpeed: Double, vSpeed: Double): TrackSimulator {
        repeat(seconds) {
            lon += metersToLon(hSpeed)
            alt += vSpeed
            t += 1000
            samples.add(
                Sample(
                    timeMs = t,
                    lat = lat,
                    lon = lon,
                    altM = alt + gauss() * altNoiseM,
                    speedMs = (hSpeed + gauss() * speedNoiseMs).coerceAtLeast(0.0),
                    accuracyM = 5f,
                ),
            )
        }
        return this
    }

    fun pause(seconds: Int) = phase(seconds, hSpeed = 0.1, vSpeed = 0.0)
    fun lift(seconds: Int, hSpeed: Double = 4.0, vSpeed: Double = 1.5) = phase(seconds, hSpeed, vSpeed)
    fun downhill(seconds: Int, hSpeed: Double = 10.0, vSpeed: Double = -3.6) = phase(seconds, hSpeed, vSpeed)

    private fun gauss(): Double {
        // Box-Muller
        val u1 = rnd.nextDouble().coerceAtLeast(1e-9)
        val u2 = rnd.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
    }
}

/** Lässt alle Punkte durch den Detektor laufen und gibt die abgeschlossenen Abschnitte zurück. */
fun detect(samples: List<Sample>, detector: ActivityDetector = ActivityDetector()): List<Segment> {
    val out = mutableListOf<Segment>()
    for (s in samples) out.addAll(detector.onSample(s).closed)
    out.addAll(detector.finish())
    return out
}
