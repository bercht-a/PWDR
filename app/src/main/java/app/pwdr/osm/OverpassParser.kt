package app.pwdr.osm

import org.json.JSONObject

/** Wandelt die Overpass-Antwort (`out tags geom`) in [OsmWay]-Objekte um. */
object OverpassParser {

    fun parse(json: String): List<OsmWay> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<OsmWay>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            if (e.optString("type") != "way") continue
            val tags = e.optJSONObject("tags") ?: continue
            val geom = e.optJSONArray("geometry") ?: continue
            val points = ArrayList<GeoPoint>(geom.length())
            for (j in 0 until geom.length()) {
                val g = geom.optJSONObject(j) ?: continue
                points.add(GeoPoint(g.getDouble("lat"), g.getDouble("lon")))
            }
            if (points.size < 2) continue

            val isPiste = tags.optString("piste:type") == "downhill"
            val aerialway = tags.optString("aerialway")
            val kind = when {
                isPiste -> WayKind.PISTE
                aerialway in LIFT_TYPES -> WayKind.LIFT
                else -> continue
            }
            val name = listOf("name", "piste:name", "piste:ref", "ref")
                .map { tags.optString(it).trim() }
                .firstOrNull { it.isNotEmpty() }
            out.add(
                OsmWay(
                    id = e.getLong("id"),
                    kind = kind,
                    name = name,
                    difficulty = tags.optString("piste:difficulty").ifEmpty { null },
                    subtype = if (kind == WayKind.LIFT) aerialway else null,
                    points = points,
                ),
            )
        }
        return out
    }

    /** Lifttypen, die Skifahrer befördern (ohne Materialbahnen und Stationsgebäude). */
    val LIFT_TYPES = setOf(
        "chair_lift", "gondola", "cable_car", "mixed_lift", "drag_lift", "t-bar", "j-bar",
        "platter", "rope_tow", "magic_carpet", "funicular",
    )

    fun query(r: Resort): String {
        val bbox = "${r.south},${r.west},${r.north},${r.east}"
        val lifts = LIFT_TYPES.joinToString("|")
        return "[out:json][timeout:90];(" +
            "way[\"piste:type\"=\"downhill\"]($bbox);" +
            "way[\"aerialway\"~\"^($lifts)$\"]($bbox);" +
            ");out tags geom;"
    }
}
