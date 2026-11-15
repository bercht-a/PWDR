package app.pwdr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ActivityDetectorTest {

    private fun List<Segment>.kinds() = map { it.activity }

    private fun assertNear(expected: Double, actual: Double, tolerance: Double, what: String) {
        assertTrue("$what: erwartet $expected ± $tolerance, war $actual", abs(expected - actual) <= tolerance)
    }

    @Test
    fun skiDay_isSplitIntoPauseLiftPauseDownhillPause() {
        val sim = TrackSimulator()
            .pause(60)
            .lift(300)
            .pause(60)
            .downhill(240)
            .pause(120)

        val segments = detect(sim.samples)

        assertEquals(
            listOf(Activity.PAUSE, Activity.LIFT, Activity.PAUSE, Activity.DOWNHILL, Activity.PAUSE),
            segments.kinds(),
        )
        // Grenzen auf ±20 s genau (Fensterverzögerung und Bestätigungszeit).
        assertNear(300.0, segments[1].durationMs / 1000.0, 20.0, "Dauer Lift")
        assertNear(240.0, segments[3].durationMs / 1000.0, 20.0, "Dauer Abfahrt")
    }

    @Test
    fun downhillStats_matchTheSimulatedRun() {
        val sim = TrackSimulator().pause(30).downhill(240).pause(60)
        val run = detect(sim.samples).single { it.activity == Activity.DOWNHILL }
        val stats = SegmentStatsCalculator.compute(run.samples)

        // 10 m/s * 240 s = 2400 m, 3.6 m/s * 240 s = 864 m
        assertNear(2400.0, stats.distanceM, 2400 * 0.10, "Distanz")
        assertNear(864.0, stats.vertDownM, 864 * 0.12, "Höhenmeter runter")
        assertNear(10.0, stats.maxSpeedMs, 1.5, "Höchstgeschwindigkeit")
        assertTrue("Aufstieg sollte klein sein: ${stats.vertUpM}", stats.vertUpM < 40)
    }

    @Test
    fun flatTraverseInsideRun_doesNotSplitTheRun() {
        val sim = TrackSimulator()
            .pause(30)
            .downhill(90)
            .phase(25, hSpeed = 3.0, vSpeed = 0.0) // flache Querung
            .downhill(90)
            .pause(60)

        val downhills = detect(sim.samples).filter { it.activity == Activity.DOWNHILL }
        assertEquals("Eine Abfahrt statt mehrerer", 1, downhills.size)
    }

    @Test
    fun liftStopInsideLift_isMergedIntoOneLift() {
        val sim = TrackSimulator()
            .pause(30)
            .lift(120)
            .pause(45) // Lift hält kurz an
            .lift(120)
            .pause(30)

        val raw = detect(sim.samples)
        val merged = SegmentStatsCalculator.mergeShortLiftPauses(raw)

        assertEquals("Roh: zwei Lifte", 2, raw.count { it.activity == Activity.LIFT })
        assertEquals("Nach Zusammenführen: ein Lift", 1, merged.count { it.activity == Activity.LIFT })
    }

    @Test
    fun standingStill_staysPause() {
        val sim = TrackSimulator().pause(600)
        val segments = detect(sim.samples)
        assertEquals(listOf(Activity.PAUSE), segments.kinds())
    }

    @Test
    fun poorAccuracyPoints_areIgnored() {
        val sim = TrackSimulator().pause(30).downhill(120).pause(60)
        // Jeder 5. Punkt bekommt eine schlechte Genauigkeit und einen wilden Wert.
        val bad = sim.samples.mapIndexed { i, s ->
            if (i % 5 == 0) s.copy(accuracyM = 80f, altM = s.altM + 300, speedMs = 40.0) else s
        }
        val segments = detect(bad)
        assertEquals(
            listOf(Activity.PAUSE, Activity.DOWNHILL, Activity.PAUSE),
            segments.kinds(),
        )
    }

    @Test
    fun slowGentleSlope_isStillDetectedAsDownhill() {
        // Flacher Hang: 6 m/s horizontal, nur 0.8 m/s Abstieg.
        val sim = TrackSimulator().pause(30).downhill(180, hSpeed = 6.0, vSpeed = -0.8).pause(60)
        val kinds = detect(sim.samples).kinds()
        assertTrue("Abfahrt erkannt: $kinds", Activity.DOWNHILL in kinds)
    }

    @Test
    fun severalRuns_areCountedSeparately() {
        val sim = TrackSimulator()
            .pause(30)
            .lift(240).pause(40).downhill(180).pause(30)
            .lift(240).pause(40).downhill(200).pause(60)

        val segments = SegmentStatsCalculator.mergeShortLiftPauses(detect(sim.samples))
        assertEquals(2, segments.count { it.activity == Activity.DOWNHILL })
        assertEquals(2, segments.count { it.activity == Activity.LIFT })
    }
}
