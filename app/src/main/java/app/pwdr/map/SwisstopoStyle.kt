package app.pwdr.map

/**
 * Basiskarte: swisstopo Pixelkarte (farbig) als Raster-Kacheln über die offene WMTS-Schnittstelle.
 * Quellenangabe "© swisstopo" muss sichtbar bleiben (siehe PLAN.md, Abschnitt 5a/5d).
 * Später: Vektor-Basiskarte, Pisten und Lifte aus OSM als eigene Ebene.
 */
object SwisstopoStyle {
    const val ATTRIBUTION = "© swisstopo"

    val json: String = """
        {
          "version": 8,
          "sources": {
            "swisstopo": {
              "type": "raster",
              "tiles": [
                "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-farbe/default/current/3857/{z}/{x}/{y}.jpeg"
              ],
              "tileSize": 256,
              "minzoom": 5,
              "maxzoom": 18,
              "attribution": "$ATTRIBUTION"
            }
          },
          "layers": [
            { "id": "background", "type": "background", "paint": { "background-color": "#0E1317" } },
            { "id": "swisstopo", "type": "raster", "source": "swisstopo" }
          ]
        }
    """.trimIndent()
}
