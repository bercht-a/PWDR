package app.pwdr.tracking

import app.pwdr.osm.GeoPoint
import app.pwdr.osm.OsmWay
import app.pwdr.osm.OverpassParser
import app.pwdr.osm.WayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos

class RunMatcherTest {

    private val lat = 46.49
    private val lon0 = 7.56
    private fun lonAt(m: Double) = lon0 + m / (111_320.0 * cos(Math.toRadians(lat)))

    /** Linie entlang des Simulatorwegs (nach Osten), [northM] Meter nördlich versetzt. */
    private fun line(id: Long, kind: WayKind, name: String?, fromM: Double, toM: Double, northM: Double = 0.0, difficulty: String? = "easy") =
        OsmWay(
            id, kind, name, difficulty, if (kind == WayKind.LIFT) "chair_lift" else null,
            listOf(
                GeoPoint(lat + northM / 111_320.0, lonAt(fromM)),
                GeoPoint(lat + northM / 111_320.0, lonAt((fromM + toM) / 2)),
                GeoPoint(lat + northM / 111_320.0, lonAt(toM)),
            ),
        )

    private fun downhillSamples(): List<Sample> =
        TrackSimulator().pause(10).downhill(60).samples.drop(10)

    @Test
    fun picksNearestPisteByMajority() {
        val ways = listOf(
            line(1, WayKind.PISTE, "Tilgi", 0.0, 800.0, northM = 8.0, difficulty = "intermediate"),
            line(2, WayKind.PISTE, "Parallelpiste", 0.0, 800.0, northM = 150.0),
        )
        val m = RunMatcher.match(downhillSamples(), ways, WayKind.PISTE)
        assertNotNull(m)
        assertEquals("Tilgi", m!!.name)
        assertEquals("intermediate", m.difficulty)
    }

    @Test
    fun noPisteWithinTolerance_returnsNull() {
        val ways = listOf(line(1, WayKind.PISTE, "Weit weg", 0.0, 800.0, northM = 400.0))
        assertNull(RunMatcher.match(downhillSamples(), ways, WayKind.PISTE))
    }

    @Test
    fun splitPisteWaysWithSameNameAreCombined() {
        // Zwei Wege je zur Hälfte, dazu eine kurze andere Piste. Nur zusammengezählt gewinnt "Gandegg".
        val ways = listOf(
            line(1, WayKind.PISTE, "Gandegg", 0.0, 300.0, northM = 5.0),
            line(2, WayKind.PISTE, "Gandegg", 300.0, 650.0, northM = 5.0),
            line(3, WayKind.PISTE, "Querung", 100.0, 400.0, northM = 10.0),
        )
        val m = RunMatcher.match(downhillSamples(), ways, WayKind.PISTE)
        assertEquals("Gandegg", m?.name)
    }

    @Test
    fun unnamedPisteMatchesWithNullNameButDifficulty() {
        val ways = listOf(line(7, WayKind.PISTE, null, 0.0, 800.0, difficulty = "advanced"))
        val m = RunMatcher.match(downhillSamples(), ways, WayKind.PISTE)
        assertNotNull(m)
        assertNull(m!!.name)
        assertEquals("advanced", m.difficulty)
    }

    @Test
    fun liftsAndPistesAreMatchedSeparately() {
        val ways = listOf(
            line(1, WayKind.PISTE, "Piste", 0.0, 800.0),
            line(2, WayKind.LIFT, "Sessellift", 0.0, 800.0, northM = 5.0),
        )
        assertEquals("Sessellift", RunMatcher.match(downhillSamples(), ways, WayKind.LIFT)?.name)
        assertEquals("Piste", RunMatcher.match(downhillSamples(), ways, WayKind.PISTE)?.name)
    }

    @Test
    fun tooFewGoodPoints_returnsNull() {
        val bad = downhillSamples().map { it.copy(accuracyM = 80f) }
        assertNull(RunMatcher.match(bad, listOf(line(1, WayKind.PISTE, "X", 0.0, 800.0)), WayKind.PISTE))
    }

    @Test
    fun parserReadsPistesLiftsAndSkipsOthers() {
        val json = """
            {"elements":[
              {"type":"way","id":10,"tags":{"piste:type":"downhill","piste:name":"Hockenhorn","piste:difficulty":"advanced"},
               "geometry":[{"lat":46.4,"lon":7.7},{"lat":46.41,"lon":7.71}]},
              {"type":"way","id":11,"tags":{"aerialway":"gondola","name":"Luftseilbahn"},
               "geometry":[{"lat":46.4,"lon":7.7},{"lat":46.42,"lon":7.72}]},
              {"type":"way","id":12,"tags":{"aerialway":"goods"},
               "geometry":[{"lat":46.4,"lon":7.7},{"lat":46.42,"lon":7.72}]},
              {"type":"way","id":13,"tags":{"piste:type":"downhill","piste:ref":"7"},
               "geometry":[{"lat":46.4,"lon":7.7}]},
              {"type":"node","id":14,"tags":{"aerialway":"station"}}
            ]}
        """.trimIndent()
        val ways = OverpassParser.parse(json)
        assertEquals(listOf(10L, 11L), ways.map { it.id })
        assertEquals("Hockenhorn", ways[0].name)
        assertEquals("advanced", ways[0].difficulty)
        assertEquals(WayKind.LIFT, ways[1].kind)
        assertEquals("gondola", ways[1].subtype)
    }
}
