package app.pwdr.tracking

import app.pwdr.osm.GeoPoint
import app.pwdr.osm.OsmGeoJson
import app.pwdr.osm.OsmWay
import app.pwdr.osm.WayKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OsmGeoJsonTest {
    @Test
    fun buildsValidGeoJsonWithEscapedNamesAndLonLatOrder() {
        val ways = listOf(
            OsmWay(1, WayKind.PISTE, "Piste \"Nr. 1\"\\", "easy", null, listOf(GeoPoint(46.4, 7.7), GeoPoint(46.41, 7.71))),
            OsmWay(2, WayKind.LIFT, "Lift", null, "gondola", listOf(GeoPoint(46.4, 7.7), GeoPoint(46.42, 7.72))),
        )
        val json = JSONObject(OsmGeoJson.build(ways, WayKind.PISTE))
        val f = json.getJSONArray("features")
        assertEquals(1, f.length())
        val feature = f.getJSONObject(0)
        assertEquals("Piste \"Nr. 1\"\\", feature.getJSONObject("properties").getString("name"))
        assertEquals("easy", feature.getJSONObject("properties").getString("difficulty"))
        val first = feature.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        assertEquals(7.7, first.getDouble(0), 1e-9) // lon zuerst
        assertEquals(46.4, first.getDouble(1), 1e-9)
    }

    @Test
    fun emptyListGivesEmptyCollection() {
        assertEquals(0, JSONObject(OsmGeoJson.build(emptyList(), WayKind.LIFT)).getJSONArray("features").length())
    }
}
