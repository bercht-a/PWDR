package app.pwdr.osm

import java.util.Locale

/** Baut GeoJSON für die Kartenebenen. Eigenschaften: difficulty (Pisten), name. */
object OsmGeoJson {

    fun build(ways: List<OsmWay>, kind: WayKind): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (w in ways) {
            if (w.kind != kind) continue
            if (!first) sb.append(',')
            first = false
            sb.append("""{"type":"Feature","properties":{""")
            sb.append("\"difficulty\":").append(quote(w.difficulty ?: ""))
            sb.append(",\"name\":").append(quote(w.name ?: ""))
            sb.append("""},"geometry":{"type":"LineString","coordinates":[""")
            w.points.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.append(String.format(Locale.ROOT, "[%.6f,%.6f]", p.lon, p.lat))
            }
            sb.append("]}}")
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
