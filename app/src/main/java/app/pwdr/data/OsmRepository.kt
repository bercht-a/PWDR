package app.pwdr.data

import app.pwdr.osm.GeoPoint
import app.pwdr.osm.OsmWay
import app.pwdr.osm.OverpassClient
import app.pwdr.osm.OverpassParser
import app.pwdr.osm.Resort
import app.pwdr.osm.WayKind

/** Hält die OSM-Pisten und -Lifte lokal vor, damit Zuordnung und Karte offline funktionieren. */
class OsmRepository(private val db: PwdrDatabase) {

    /**
     * Lädt das Skigebiet, falls es noch fehlt oder älter als [MAX_AGE_MS] ist. Schlägt der Download fehl
     * (kein Netz), bleiben vorhandene Daten unverändert. Gibt zurück, ob neu geladen wurde.
     */
    suspend fun ensureLoaded(resort: Resort, nowMs: Long = System.currentTimeMillis()): Boolean {
        val existing = db.osm().resort(resort.id)
        if (existing != null && nowMs - existing.loadedAtMs < MAX_AGE_MS) return false
        val ways = try {
            OverpassParser.parse(OverpassClient.fetch(OverpassParser.query(resort)))
        } catch (e: Exception) {
            android.util.Log.w("PWDR-OSM", "Download ${resort.id} fehlgeschlagen: $e")
            return false
        }
        if (ways.isEmpty()) {
            android.util.Log.w("PWDR-OSM", "Keine Daten für ${resort.id}")
            return false
        }
        db.osm().replaceResort(
            ResortEntity(resort.id, resort.name, resort.south, resort.west, resort.north, resort.east, nowMs),
            ways.map { it.toEntity(resort.id) },
        )
        return true
    }

    /** Alle gespeicherten Linien, deren Rahmen das Gebiet berührt. Doppelte aus überlappenden Gebieten fallen weg. */
    suspend fun waysIn(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<OsmWay> =
        db.osm().waysIn(minLat, maxLat, minLon, maxLon).map { it.toWay() }.distinctBy { it.id }

    companion object {
        const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
    }
}

private fun OsmWay.toEntity(resortId: String) = OsmWayEntity(
    resortId = resortId,
    osmId = id,
    kind = kind.name,
    name = name,
    difficulty = difficulty,
    subtype = subtype,
    coords = points.joinToString(";") { "${it.lat},${it.lon}" },
    minLat = points.minOf { it.lat },
    minLon = points.minOf { it.lon },
    maxLat = points.maxOf { it.lat },
    maxLon = points.maxOf { it.lon },
)

internal fun OsmWayEntity.toWay() = OsmWay(
    id = osmId,
    kind = WayKind.valueOf(kind),
    name = name,
    difficulty = difficulty,
    subtype = subtype,
    points = coords.split(';').map {
        val (la, lo) = it.split(',')
        GeoPoint(la.toDouble(), lo.toDouble())
    },
)
