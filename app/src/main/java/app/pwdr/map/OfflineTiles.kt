package app.pwdr.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Schätzung für den Offline-Download: wie viele Kacheln ein Ausschnitt braucht. Die Kacheln der swisstopo sind 256 px gross,
 * MapLibre lädt dafür eine Stufe feiner als die Kartenstufe (Kartenstufe 14 braucht Kachelstufe 15). Die Quelle endet bei
 * Stufe 18. Die Zahl ist eine Schätzung, der Download meldet danach die genaue.
 */
object OfflineTiles {
    const val MAX_SOURCE_ZOOM = 18

    /** Obergrenze je Quelle und Download, damit die swisstopo-Server und der Speicher des Handys nicht überlastet werden. */
    const val MAX_TILES = 6000

    fun tileZoom(mapZoom: Int): Int = min(mapZoom + 1, MAX_SOURCE_ZOOM)

    private fun tileX(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * 2.0.pow(z)).toInt()

    private fun tileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 2.0.pow(z)).toInt()
    }

    /** Kacheln einer Quelle für den Ausschnitt und die Kartenstufen [minZoom] bis [maxZoom]. */
    fun count(south: Double, west: Double, north: Double, east: Double, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val tz = tileZoom(z)
            val x0 = tileX(west, tz)
            val x1 = tileX(east, tz)
            val y0 = tileY(north, tz)
            val y1 = tileY(south, tz)
            total += (max(0, x1 - x0) + 1).toLong() * (max(0, y1 - y0) + 1).toLong()
        }
        return total
    }

    /**
     * Grobe Grösse in Bytes. Gemessen im Emulator: 132 Luftbild-Kacheln brauchten rund 3 MB (ca. 23 KB je Kachel). Karte und
     * Hangneigung sind geschätzt und noch nicht gemessen.
     */
    fun estimateBytes(tilesPerSource: Long, base: BaseMap, slope: Boolean): Long {
        val per = when (base) {
            BaseMap.AERIAL -> 25_000L
            BaseMap.COLOR -> 20_000L
            BaseMap.GREY -> 15_000L
        }
        return tilesPerSource * (per + if (slope) 15_000L else 0L)
    }

    /** Anzahl Quellen im Download (Grundkarte, plus Hangneigung wenn eingeschaltet). */
    fun sources(slope: Boolean): Int = if (slope) 2 else 1
}
