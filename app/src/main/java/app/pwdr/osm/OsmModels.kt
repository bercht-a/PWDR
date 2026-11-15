package app.pwdr.osm

enum class WayKind { PISTE, LIFT }

data class GeoPoint(val lat: Double, val lon: Double)

/** Eine OSM-Linie (Piste oder Lift) mit Geometrie. [name] ist null, wenn OSM keinen Namen kennt. */
data class OsmWay(
    val id: Long,
    val kind: WayKind,
    val name: String?,
    val difficulty: String?,
    val subtype: String?,
    val points: List<GeoPoint>,
)

/** Skigebiet mit Rahmen, für den Pisten und Lifte geladen werden. */
data class Resort(val id: String, val name: String, val south: Double, val west: Double, val north: Double, val east: Double)

object Resorts {
    val ALL = listOf(
        Resort("lauchernalp", "Lauchernalp", 46.38, 7.70, 46.46, 7.82),
        Resort("adelboden-lenk", "Adelboden-Lenk", 46.41, 7.38, 46.53, 7.64),
    )
}
