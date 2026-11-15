package app.pwdr.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FriendsOnMapTest {
    private val now = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    private fun ts(ageSec: Long) = Instant.ofEpochMilli(now - ageSec * 1000).toString()

    private val anna = Friend("a", "Anna", null, "#FF5A5F", "accepted")
    private val ben = Friend("b", "Ben \"B\"", null, "#2EC4B6", "accepted")
    private val dan = Friend("d", "Dan", null, "#FFD23F", "blocked")

    private fun row(id: String, ageSec: Long, lat: Double? = 46.4, lon: Double? = 7.7) =
        LiveRow(userId = id, lat = lat, lon = lon, updatedAt = ts(ageSec))

    @Test
    fun freshStaleHiddenAreSeparated() {
        val live = mapOf("a" to row("a", 10), "b" to row("b", 120), "c" to row("c", 10))
        val m = FriendsOnMap.markers(live, listOf(anna, ben), now)
        assertEquals(listOf("Anna", "Ben \"B\""), m.map { it.name })
        assertFalse(m[0].stale)
        assertTrue(m[1].stale)
        assertEquals("vor 2 min", m[1].ageText)
        val hidden = FriendsOnMap.markers(mapOf("a" to row("a", 301)), listOf(anna), now)
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun blockedAndUnknownAndPositionlessAreSkipped() {
        val live = mapOf("d" to row("d", 5), "x" to row("x", 5), "a" to row("a", 5, lat = null))
        assertTrue(FriendsOnMap.markers(live, listOf(anna, dan), now).isEmpty())
    }

    @Test
    fun geoJsonIsValidWithEscapedNames() {
        val m = FriendsOnMap.markers(mapOf("b" to row("b", 5, 46.4123456, 7.7123456)), listOf(ben), now)
        val json = JSONObject(FriendsOnMap.geoJson(m))
        val f = json.getJSONArray("features").getJSONObject(0)
        assertEquals("Ben \"B\"", f.getJSONObject("properties").getString("name"))
        assertEquals("#2EC4B6", f.getJSONObject("properties").getString("color"))
        assertFalse(f.getJSONObject("properties").getBoolean("stale"))
        val c = f.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(7.712346, c.getDouble(0), 1e-6)
        assertEquals(46.412346, c.getDouble(1), 1e-6)
    }

    @Test
    fun emptyGivesEmptyCollection() {
        assertEquals(0, JSONObject(FriendsOnMap.geoJson(emptyList())).getJSONArray("features").length())
    }
}
