package app.pwdr.tracking

import app.pwdr.osm.OsmWay
import app.pwdr.osm.OverpassParser
import app.pwdr.osm.WayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.random.Random

/** Echte OSM-Daten von Lauchernalp (Overpass-Abzug vom 15.11.2026): jede Piste muss sich selbst wiederfinden. */
class RealOsmDataTest {

    private val ways: List<OsmWay> = OverpassParser.parse(
        javaClass.getResourceAsStream("/lauchernalp.json")!!.bufferedReader(Charsets.UTF_8).readText(),
    )

    /** Fährt eine Linie in ca. 10-m-Schritten ab, mit GPS-Rauschen von bis zu [noiseM] Metern. */
    private fun driveAlong(way: OsmWay, noiseM: Double, seed: Int = 1): List<Sample> {
        val rnd = Random(seed)
        val out = ArrayList<Sample>()
        var t = 1_700_000_000_000L
        for (i in 0 until way.points.size - 1) {
            val a = way.points[i]; val b = way.points[i + 1]
            val len = haversineM(a.lat, a.lon, b.lat, b.lon)
            val n = maxOf(1, (len / 10).toInt())
            for (k in 0 until n) {
                val f = k.toDouble() / n
                val lat = a.lat + (b.lat - a.lat) * f + noise(rnd, noiseM) / 111_320.0
                val lon = a.lon + (b.lon - a.lon) * f + noise(rnd, noiseM) / (111_320.0 * cos(Math.toRadians(a.lat)))
                t += 1000
                out.add(Sample(t, lat, lon, 2000.0 - out.size, 10.0, 5f))
            }
        }
        return out
    }

    private fun noise(rnd: Random, m: Double) = if (m == 0.0) 0.0 else rnd.nextDouble(-m, m)

    @Test
    fun parsesExpectedCounts() {
        assertEquals(21, ways.count { it.kind == WayKind.PISTE })
        assertEquals(7, ways.count { it.kind == WayKind.LIFT })
    }

    @Test
    fun namedPistesAreRecoveredFromTheirOwnGeometry() {
        // Lange, benannte Pisten. Kurze Wege (z. B. Übergänge) können legitim zu einer Nachbarpiste gehören.
        val candidates = ways.filter { it.kind == WayKind.PISTE && it.name != null && driveAlong(it, 0.0).size >= 20 }
        assertTrue("zu wenige Kandidaten: ${candidates.size}", candidates.size >= 8)
        val wrong = candidates.filter {
            RunMatcher.match(driveAlong(it, noiseM = 6.0), ways, WayKind.PISTE)?.name != it.name
        }
        // Kreuzungen und Parallelpisten erlauben vereinzelt Verwechslungen. Die grosse Mehrheit muss stimmen.
        assertTrue(
            "falsch zugeordnet ${wrong.size} von ${candidates.size}: ${wrong.map { it.name }}",
            wrong.size <= candidates.size / 5,
        )
    }

    @Test
    fun liftsAreRecoveredFromTheirOwnGeometry() {
        val lifts = ways.filter { it.kind == WayKind.LIFT && it.name != null && driveAlong(it, 0.0).size >= 20 }
        assertTrue(lifts.isNotEmpty())
        for (l in lifts) {
            assertEquals(l.name, RunMatcher.match(driveAlong(l, noiseM = 6.0), ways, WayKind.LIFT)?.name)
        }
    }
}
