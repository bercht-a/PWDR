package app.pwdr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BarometerAndGpxTest {

    /** Druck in hPa für eine Höhe (Umkehrung der Höhenformel). */
    private fun pressureAt(altM: Double): Float =
        (1013.25 * Math.pow(1.0 - altM / 44330.0, 5.255)).toFloat()

    @Test
    fun withoutPressure_returnsGpsAltitude() {
        val b = BarometerAltitude()
        assertEquals(1500.0, b.altitude(0, 1500.0, 5f)!!, 0.0001)
        assertEquals(null, b.altitude(1000, null, 5f))
    }

    @Test
    fun baroTracksDescentDespiteLaggyGps() {
        val rnd = Random(3)
        val b = BarometerAltitude()
        var fused = 0.0
        // 60 s Stillstand auf 2000 m, dann 60 s Abfahrt mit -4 m/s. GPS-Höhe hinkt stark nach.
        for (t in 0 until 120) {
            val trueAlt = if (t < 60) 2000.0 else 2000.0 - (t - 60) * 4.0
            val gpsAlt = if (t < 60) 2000.0 else 2000.0 - (t - 60) * 1.0 + rnd.nextDouble(-3.0, 3.0)
            b.onPressure(pressureAt(trueAlt + rnd.nextDouble(-0.3, 0.3)))
            fused = b.altitude(t * 1000L, gpsAlt, 5f)!!
        }
        // Wahrer Abstieg 236 m (bei t=119). Die Fusion darf höchstens ein paar Meter abweichen,
        // reines GPS läge ~180 m daneben.
        assertEquals(2000.0 - 59 * 4.0, fused, 12.0)
    }

    @Test
    fun poorGpsDoesNotMoveAnchor() {
        val b = BarometerAltitude()
        b.onPressure(pressureAt(1800.0))
        val first = b.altitude(0, 1800.0, 5f)!!
        // Schlechter Fix mit falscher Höhe verschiebt die Barometerhöhe nicht.
        val second = b.altitude(10_000, 1900.0, 80f)!!
        assertEquals(first, second, 0.5)
    }

    @Test
    fun gpxContainsPointsTimesAndEscapedName() {
        val s = Sample(1_700_000_000_000, 46.49, 7.56, 1350.5, 3.2, 4f, gpsAltM = 1348.0)
        val gpx = GpxExporter.toGpx("A & B <x>", listOf(listOf(s, s.copy(timeMs = s.timeMs + 1000))))
        assertTrue(gpx.contains("<name>A &amp; B &lt;x&gt;</name>"))
        assertTrue(gpx.contains("lat=\"46.4900000\" lon=\"7.5600000\""))
        assertTrue(gpx.contains("<ele>1350.5</ele>"))
        assertTrue(gpx.contains("<time>2023-11-14T22:13:20Z</time>"))
        assertTrue(gpx.contains("<pwdr:gpsele>1348.0</pwdr:gpsele>"))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertEquals(1, Regex("<trkseg>").findAll(gpx).count())
    }
}
