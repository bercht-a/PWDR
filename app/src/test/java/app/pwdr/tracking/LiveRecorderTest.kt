package app.pwdr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRecorderTest {

    @Test
    fun countsRunsAndLiftsLive() {
        val sim = TrackSimulator()
            .pause(30)
            .lift(240).pause(40).downhill(180).pause(30)
            .lift(240).pause(40).downhill(200).pause(60)

        val recorder = LiveRecorder()
        var last = LiveState()
        for (s in sim.samples) last = recorder.onSample(s)

        assertEquals("Abfahrten", 2, last.runCount)
        assertTrue("Lifte mindestens 2 (ein Lift-Halt kann ihn teilen): ${last.liftCount}", last.liftCount >= 2)
        assertTrue("Höhenmeter runter ~1400: ${last.totalVertDownM}", last.totalVertDownM in 1100.0..1700.0)
        assertTrue("Vmax ~36 km/h: ${last.maxSpeedMs}", last.maxSpeedMs in 8.0..14.0)
    }

    @Test
    fun runsAnalyzedAfterwardsMatchTheSimulation() {
        val sim = TrackSimulator().pause(30).lift(240).pause(40).downhill(180).pause(30)
        val segments = SessionAnalyzer.analyze(sim.samples)
        assertEquals(
            listOf(Activity.PAUSE, Activity.LIFT, Activity.PAUSE, Activity.DOWNHILL, Activity.PAUSE),
            segments.map { it.activity },
        )
    }

    @Test
    fun tinyDownhillNoise_becomesPause() {
        // 20 s mit leichtem Gefälle, nur ca. 6 m Abstieg, soll keine Abfahrt ergeben.
        val sim = TrackSimulator().pause(30).downhill(20, hSpeed = 3.0, vSpeed = -0.3).pause(60)
        val segments = SessionAnalyzer.analyze(sim.samples)
        assertTrue(segments.none { it.activity == Activity.DOWNHILL })
    }
}
