package app.pwdr.social

import java.util.Locale

/** Ein Freund, der auf der Karte erscheint. */
data class FriendMarker(
    val id: String,
    val name: String,
    val color: String,
    val lat: Double,
    val lon: Double,
    val stale: Boolean,
    val ageText: String,
    /** downhill, lift oder pause, sofern bekannt. */
    val state: String? = null,
    /** Geschwindigkeit in m/s, sofern bekannt. */
    val speedMs: Double? = null,
)

/**
 * Wählt aus den Live-Positionen die Freunde aus, die auf der Karte erscheinen (PLAN.md 5c, "Staleness"):
 * ab 60 s ohne Update ausgegraut, ab 5 min ausgeblendet. Nur akzeptierte Freunde.
 */
object FriendsOnMap {
    fun markers(live: Map<String, LiveRow>, friends: List<Friend>, nowMs: Long): List<FriendMarker> {
        val byId = friends.filter { it.status == "accepted" }.associateBy { it.friendId }
        return live.values.mapNotNull { row ->
            val friend = byId[row.userId] ?: return@mapNotNull null
            val lat = row.lat ?: return@mapNotNull null
            val lon = row.lon ?: return@mapNotNull null
            val updated = try {
                parseInstantMs(row.updatedAt)
            } catch (e: Exception) {
                return@mapNotNull null
            }
            when (FriendFreshness.of(updated, nowMs)) {
                Freshness.HIDDEN -> null
                Freshness.STALE -> FriendMarker(friend.friendId, friend.displayName, friend.color, lat, lon, true, FriendFreshness.ageLabel(updated, nowMs), row.state, row.speed)
                Freshness.FRESH -> FriendMarker(friend.friendId, friend.displayName, friend.color, lat, lon, false, FriendFreshness.ageLabel(updated, nowMs), row.state, row.speed)
            }
        }.sortedBy { it.name }
    }

    /** "Abfahrt 54 km/h", "Lift" oder "Pause" für die Liste auf der Karte. Leer, wenn der Zustand unbekannt ist. */
    fun stateText(m: FriendMarker): String? = when (m.state) {
        "downhill" -> m.speedMs?.let { "Abfahrt ${Math.round(it * 3.6)} km/h" } ?: "Abfahrt"
        "lift" -> "Lift"
        "pause" -> "Pause"
        else -> null
    }

    /** GeoJSON mit den Eigenschaften name, color und stale (für die Kartenebene). */
    fun geoJson(markers: List<FriendMarker>): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        markers.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append("""{"type":"Feature","properties":{"name":${quote(m.name)},"color":${quote(m.color)},"stale":${m.stale}},""")
            sb.append("\"geometry\":{\"type\":\"Point\",\"coordinates\":")
            sb.append(String.format(Locale.ROOT, "[%.6f,%.6f]}}", m.lon, m.lat))
        }
        return sb.append("]}").toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
