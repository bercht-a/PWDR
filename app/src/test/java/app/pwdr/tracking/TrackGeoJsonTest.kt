package app.pwdr.tracking

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackGeoJsonTest {

    private fun ranges(samples: List<Sample>) =
        SessionAnalyzer.analyze(samples).map { ActivityRange(it.activity, it.startMs, it.endMs) }

    @Test
    fun drawsDownhillAndLiftButNotPause() {
        val sim = TrackSimulator().pause(30).lift(120).pause(20).downhill(100).pause(30)
        val json = JSONObject(TrackGeoJson.build(listOf(sim.samples), ranges(sim.samples)))
        val feats = json.getJSONArray("features")
        val acts = (0 until feats.length()).map { feats.getJSONObject(it).getJSONObject("properties").getString("activity") }
        assertTrue(acts.contains("DOWNHILL"))
        assertTrue(acts.contains("LIFT"))
        assertTrue(acts.none { it == "PAUSE" })
        // Stücke von mindestens 30 m: Abfahrt ca. 1000 m, Lift ca. 480 m.
        assertTrue("Stücke: ${feats.length()}", feats.length() in 30..60)
        // Kein Stück ist kürzer als ca. 30 m (bis auf das letzte je Abschnitt).
        val lengths = (0 until feats.length()).map { i ->
            val c = feats.getJSONObject(i).getJSONObject("geometry").getJSONArray("coordinates")
            (1 until c.length()).sumOf {
                haversineM(c.getJSONArray(it - 1).getDouble(1), c.getJSONArray(it - 1).getDouble(0),
                    c.getJSONArray(it).getDouble(1), c.getJSONArray(it).getDouble(0))
            }
        }
        assertTrue("kurze Stücke: ${lengths.count { it < 25.0 }}", lengths.count { it < 25.0 } <= 2)
    }

    @Test
    fun speedIsInKmhAndCoordinatesAreLonLat() {
        val sim = TrackSimulator().downhill(60)
        val json = JSONObject(TrackGeoJson.build(listOf(sim.samples), ranges(sim.samples)))
        val f = json.getJSONArray("features").getJSONObject(3)
        assertEquals(36.0, f.getJSONObject("properties").getDouble("speed"), 6.0)
        val c = f.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        assertEquals(7.56, c.getDouble(0), 0.01)
        assertEquals(46.49, c.getDouble(1), 0.01)
    }

    @Test
    fun largeTimeGapBreaksTheLine() {
        val a = TrackSimulator().downhill(30).samples
        val b = TrackSimulator(startTimeMs = 1_700_000_100_000).downhill(30).samples
        val joined = a + b
        val feats = JSONObject(TrackGeoJson.build(listOf(joined), ranges(joined))).getJSONArray("features")
        // Über die Lücke (a.last -> b.first, 100 s) wird nicht verbunden: kein Stück springt zwischen den Teilen.
        val xs = (0 until feats.length()).map { i ->
            val c = feats.getJSONObject(i).getJSONObject("geometry").getJSONArray("coordinates")
            (0 until c.length()).map { c.getJSONArray(it).getDouble(0) }
        }
        // Die Simulatoren starten beide am selben Ort: ein verbindendes Stück würde 600 m zurückspringen.
        assertTrue(xs.all { pts -> pts.zipWithNext().all { (a, b) -> b >= a - 1e-9 } })
    }

    @Test
    fun boundsCoverAllGoodPoints() {
        val s = TrackSimulator().downhill(60).samples
        val b = TrackGeoJson.bounds(listOf(s))
        assertNotNull(b)
        assertTrue(b!!.east > b.west)
        assertNull(TrackGeoJson.bounds(listOf(s.map { it.copy(accuracyM = 90f) })))
    }
}
