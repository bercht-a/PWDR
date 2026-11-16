package app.pwdr.map

import android.content.Context

/** Wählbare Grundkarte. Alle drei kommen von swisstopo. */
enum class BaseMap(val key: String, val label: String, val layerId: String) {
    COLOR("color", "Karte", "base-color"),
    AERIAL("aerial", "Luftbild", "base-aerial"),
    GREY("grey", "Karte grau", "base-grey"),
    ;

    companion object {
        fun of(key: String?): BaseMap = entries.firstOrNull { it.key == key } ?: COLOR
    }
}

/**
 * Karten von swisstopo als Raster-Kacheln über die offene WMTS-Schnittstelle: Pixelkarte (farbig und grau),
 * Luftbild (SWISSIMAGE) und die Hangneigung ab 30° als durchsichtige Ebene darüber. Quellenangabe "© swisstopo"
 * muss sichtbar bleiben (siehe PLAN.md, Abschnitt 5a/5d). Alle Ebenen stehen im Stil, umgeschaltet wird nur die
 * Sichtbarkeit. Pisten, Freunde und Meldungen bleiben dabei unberührt. Nicht sichtbare Ebenen laden keine Kacheln.
 */
object SwisstopoStyle {
    const val ATTRIBUTION = "© swisstopo"
    const val SLOPE_LAYER = "overlay-slope"

    private const val WMTS = "https://wmts.geo.admin.ch/1.0.0"

    fun json(base: BaseMap = BaseMap.COLOR, slope: Boolean = false): String {
        fun vis(on: Boolean) = if (on) "visible" else "none"
        return """
        {
          "version": 8,
          "sources": {
            "src-color":  { "type": "raster", "tiles": ["$WMTS/ch.swisstopo.pixelkarte-farbe/default/current/3857/{z}/{x}/{y}.jpeg"], "tileSize": 256, "minzoom": 5, "maxzoom": 18, "attribution": "$ATTRIBUTION" },
            "src-grey":   { "type": "raster", "tiles": ["$WMTS/ch.swisstopo.pixelkarte-grau/default/current/3857/{z}/{x}/{y}.jpeg"], "tileSize": 256, "minzoom": 5, "maxzoom": 18, "attribution": "$ATTRIBUTION" },
            "src-aerial": { "type": "raster", "tiles": ["$WMTS/ch.swisstopo.swissimage/default/current/3857/{z}/{x}/{y}.jpeg"], "tileSize": 256, "minzoom": 5, "maxzoom": 18, "attribution": "$ATTRIBUTION" },
            "src-slope":  { "type": "raster", "tiles": ["$WMTS/ch.swisstopo-karto.hangneigung/default/current/3857/{z}/{x}/{y}.png"], "tileSize": 256, "minzoom": 5, "maxzoom": 18, "attribution": "$ATTRIBUTION" }
          },
          "layers": [
            { "id": "background", "type": "background", "paint": { "background-color": "#0E1317" } },
            { "id": "${BaseMap.COLOR.layerId}", "type": "raster", "source": "src-color", "layout": { "visibility": "${vis(base == BaseMap.COLOR)}" } },
            { "id": "${BaseMap.GREY.layerId}", "type": "raster", "source": "src-grey", "layout": { "visibility": "${vis(base == BaseMap.GREY)}" } },
            { "id": "${BaseMap.AERIAL.layerId}", "type": "raster", "source": "src-aerial", "layout": { "visibility": "${vis(base == BaseMap.AERIAL)}" } },
            { "id": "$SLOPE_LAYER", "type": "raster", "source": "src-slope", "paint": { "raster-opacity": 0.7 }, "layout": { "visibility": "${vis(slope)}" } }
          ]
        }
        """.trimIndent()
    }

    /** Stil mit der Standardkarte (für Ansichten ohne Auswahl). */
    val json: String get() = json()
}

/** Merkt sich die gewählte Karte und die Hangneigungs-Ebene. */
class MapPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pwdr_prefs", Context.MODE_PRIVATE)

    var base: BaseMap
        get() = try {
            BaseMap.of(prefs.getString("map_base", null))
        } catch (e: Exception) {
            BaseMap.COLOR
        }
        set(value) {
            try {
                prefs.edit().putString("map_base", value.key).apply()
            } catch (_: Exception) {
            }
        }

    var slope: Boolean
        get() = try {
            prefs.getBoolean("map_slope", false)
        } catch (e: Exception) {
            false
        }
        set(value) {
            try {
                prefs.edit().putBoolean("map_slope", value).apply()
            } catch (_: Exception) {
            }
        }

    /** Lawinenregionen des SLF als Fläche. Standardmässig aus, damit nichts geladen wird, was niemand sehen will. */
    var avalanche: Boolean
        get() = try {
            prefs.getBoolean("map_avalanche", false)
        } catch (e: Exception) {
            false
        }
        set(value) {
            try {
                prefs.edit().putBoolean("map_avalanche", value).apply()
            } catch (_: Exception) {
            }
        }
}
