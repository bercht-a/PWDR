package app.pwdr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GForceTest {
    private val g = GForceTracker.STANDARD_GRAVITY.toFloat()

    private fun feed(t: GForceTracker, n: Int, ax: Float = 0f, ay: Float = 0f, az: Float = g) =
        repeat(n) { t.onAccel(ax, ay, az) }

    @Test fun restIsOneG() {
        val t = GForceTracker()
        feed(t, 50)
        val r = t.drain()!!
        assertEquals(1.0, r.max, 0.001)
        assertEquals(1.0, r.mean, 0.001)
    }

    @Test fun orientationDoesNotMatter() {
        val t = GForceTracker()
        feed(t, 50, ax = g, az = 0f)
        assertEquals(1.0, t.drain()!!.max, 0.001)
    }

    @Test fun sustainedLoadIsReported() {
        val t = GForceTracker()
        feed(t, 50, az = 2f * g)
        val r = t.drain()!!
        assertEquals(2.0, r.max, 0.001)
        assertEquals(2.0, r.mean, 0.001)
    }

    @Test fun singleSpikeIsSmoothedAway() {
        val t = GForceTracker()
        feed(t, 20)
        t.onAccel(0f, 0f, 8f * g) // ein Stoss
        feed(t, 20)
        // Ein Ausreisser von 8 g über 5 Werte gemittelt ergibt höchstens 2,4 g, nicht 8 g
        assertTrue(t.drain()!!.max < 2.5)
    }

    @Test fun tooFewSamplesGiveNothing() {
        val t = GForceTracker()
        feed(t, 3)
        assertNull(t.drain())
    }

    @Test fun drainStartsNewWindow() {
        val t = GForceTracker()
        feed(t, 30, az = 3f * g)
        assertEquals(3.0, t.drain()!!.max, 0.001)
        // Die Glättung läuft über den Abruf hinaus weiter: die ersten Werte danach mischen noch alte ein
        feed(t, 30)
        t.drain()
        feed(t, 30)
        assertEquals(1.0, t.drain()!!.max, 0.001)
    }

    @Test fun implausibleValuesAreCapped() {
        val t = GForceTracker()
        feed(t, 30, az = 50f * g)
        assertEquals(GForceTracker.MAX_PLAUSIBLE_G, t.drain()!!.max, 0.001)
    }

    @Test fun nonFiniteValuesAreIgnored() {
        val t = GForceTracker()
        repeat(10) { t.onAccel(Float.NaN, 0f, g) }
        assertNull(t.drain())
    }

    private fun sample(t: Long, gMax: Double?, gMean: Double?) =
        Sample(t, 46.0, 7.0, 2000.0, 10.0, 5f, null, gMax, gMean)

    @Test fun statsTakeMaxAndMean() {
        val s = GForceStats.of(listOf(sample(0, 1.2, 1.1), sample(1000, 2.4, 1.5), sample(2000, null, null)))!!
        assertEquals(2.4, s.max, 1e-9)
        assertEquals(1.3, s.mean, 1e-9)
    }

    @Test fun statsAreNullWithoutData() {
        assertNull(GForceStats.of(listOf(sample(0, null, null))))
        assertNull(GForceStats.of(emptyList()))
    }

    @Test fun dayProfileKeepsPeakBetweenThinnedPoints() {
        val samples = (0..10).map { sample(it * 1000L, if (it == 3) 3.5 else 1.1, 1.0) }
        val ranges = listOf(ActivityRange(Activity.DOWNHILL, 0, 10_000))
        val p = DayProfile.build(listOf(samples), ranges, stepMs = 5_000)
        assertNotNull(p.firstOrNull { it.g != null })
        // Der Punkt bei 5 s fasst die Werte der Sekunden 0 bis 5 zusammen, darin liegt die Spitze von 3,5 g
        assertEquals(3.5, p.first { it.timeMs == 5_000L }.g!!, 1e-9)
    }

    @Test fun dayProfileHasNoGWithoutData() {
        val samples = (0..10).map { sample(it * 1000L, null, null) }
        val p = DayProfile.build(listOf(samples), listOf(ActivityRange(Activity.DOWNHILL, 0, 10_000)), stepMs = 5_000)
        assertTrue(p.all { it.g == null })
    }
}
