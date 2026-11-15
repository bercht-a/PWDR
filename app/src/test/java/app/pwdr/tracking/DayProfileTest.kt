package app.pwdr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayProfileTest {

    private fun ranges(segs: List<Segment>) = segs.map { ActivityRange(it.activity, it.startMs, it.endMs) }

    @Test
    fun thinsToRoughlyFiveSecondSteps() {
        val sim = TrackSimulator().pause(30).lift(120).pause(20).downhill(100).pause(30)
        val profile = DayProfile.build(listOf(sim.samples), ranges(SessionAnalyzer.analyze(sim.samples)))
        assertTrue("Punkte: ${profile.size}", profile.size in 50..60)
        assertTrue(profile.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs >= 5_000 })
    }

    @Test
    fun cumulativeDistanceCountsOnlyDownhill() {
        val sim = TrackSimulator().pause(30).lift(120).pause(20).downhill(100).pause(30)
        val profile = DayProfile.build(listOf(sim.samples), ranges(SessionAnalyzer.analyze(sim.samples)))
        // 100 s Abfahrt mit 10 m/s: ca. 1000 m. Lift (480 m) darf nicht mitzählen.
        val last = profile.last().cumDistM
        assertEquals(1000.0, last, 120.0)
        // Während des Lifts bleibt der Zähler bei 0.
        val duringLift = profile.filter { it.activity == Activity.LIFT }
        assertTrue(duringLift.isNotEmpty() && duringLift.all { it.cumDistM == 0.0 })
    }

    @Test
    fun activitiesAreAssignedFromRanges() {
        val sim = TrackSimulator().pause(30).lift(120).pause(20).downhill(100).pause(30)
        val profile = DayProfile.build(listOf(sim.samples), ranges(SessionAnalyzer.analyze(sim.samples)))
        assertTrue(profile.any { it.activity == Activity.LIFT })
        assertTrue(profile.any { it.activity == Activity.DOWNHILL })
    }

    @Test
    fun poorAccuracyPointsAreSkipped() {
        val sim = TrackSimulator().downhill(60)
        val bad = sim.samples.map { it.copy(accuracyM = 80f) }
        val profile = DayProfile.build(listOf(bad), emptyList())
        assertTrue(profile.isEmpty())
    }

    @Test
    fun multipleSessionsKeepCumulativeDistance() {
        val a = TrackSimulator().downhill(60)
        val b = TrackSimulator(startTimeMs = 1_700_010_000_000).downhill(60)
        val r = ranges(SessionAnalyzer.analyze(a.samples)) + ranges(SessionAnalyzer.analyze(b.samples))
        val profile = DayProfile.build(listOf(a.samples, b.samples), r)
        // Zwei Abfahrten à ~600 m, die zweite startet nicht bei 0.
        assertEquals(1200.0, profile.last().cumDistM, 150.0)
    }
}
