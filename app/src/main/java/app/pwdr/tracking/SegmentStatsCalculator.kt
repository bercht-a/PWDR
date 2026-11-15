package app.pwdr.tracking

import kotlin.math.abs

object SegmentStatsCalculator {

    /** Höhenänderungen unter diesem Wert (m) zählen nicht (GPS-Rauschen). */
    private const val ALT_THRESHOLD_M = 3.0
    private const val ALT_ALPHA = 0.2

    /** Sprünge zwischen zwei Punkten über diesem Tempo (m/s) gelten als Messfehler. */
    private const val MAX_PLAUSIBLE_SPEED_MS = 60.0

    fun compute(samples: List<Sample>): SegmentStats {
        if (samples.size < 2) {
            return SegmentStats(0.0, 0.0, 0.0, samples.firstOrNull()?.speedMs ?: 0.0, 0.0, 0)
        }

        var dist = 0.0
        var up = 0.0
        var down = 0.0
        var ref = samples.first().altM
        var smooth = ref

        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = (b.timeMs - a.timeMs) / 1000.0
            val d = haversineM(a.lat, a.lon, b.lat, b.lon)
            if (dt > 0 && d / dt <= MAX_PLAUSIBLE_SPEED_MS) dist += d

            smooth += ALT_ALPHA * (b.altM - smooth)
            val delta = smooth - ref
            if (abs(delta) >= ALT_THRESHOLD_M) {
                if (delta > 0) up += delta else down -= delta
                ref = smooth
            }
        }

        val durationMs = samples.last().timeMs - samples.first().timeMs
        val avg = if (durationMs > 0) dist / (durationMs / 1000.0) else 0.0
        return SegmentStats(
            distanceM = dist,
            vertDownM = down,
            vertUpM = up,
            maxSpeedMs = maxSpeed(samples),
            avgSpeedMs = avg,
            durationMs = durationMs,
        )
    }

    /** Höchstgeschwindigkeit als Maximum des gleitenden Medians über 3 Punkte (gegen Ausreißer). */
    private fun maxSpeed(samples: List<Sample>): Double {
        if (samples.size < 3) return samples.maxOf { it.speedMs }
        var best = 0.0
        for (i in 1 until samples.size - 1) {
            val m = listOf(samples[i - 1].speedMs, samples[i].speedMs, samples[i + 1].speedMs)
                .sorted()[1]
            if (m <= MAX_PLAUSIBLE_SPEED_MS && m > best) best = m
        }
        return best
    }

    /**
     * Kurze Pausen zwischen zwei Liftabschnitten (Lift hält an) werden zum Lift gerechnet.
     * Abschnitte werden dabei zusammengefasst.
     */
    fun mergeShortLiftPauses(
        segments: List<Segment>,
        maxPauseMs: Long = 90_000,
    ): List<Segment> {
        val out = mutableListOf<Segment>()
        var i = 0
        while (i < segments.size) {
            val s = segments[i]
            if (s.activity == Activity.LIFT) {
                var merged = s
                while (i + 2 < segments.size &&
                    segments[i + 1].activity == Activity.PAUSE &&
                    segments[i + 1].durationMs <= maxPauseMs &&
                    segments[i + 2].activity == Activity.LIFT
                ) {
                    merged = Segment(
                        Activity.LIFT,
                        merged.samples + segments[i + 1].samples + segments[i + 2].samples,
                    )
                    i += 2
                }
                out.add(merged)
            } else {
                out.add(s)
            }
            i++
        }
        return out
    }
}
