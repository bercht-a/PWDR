package app.pwdr.osm

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lädt OSM-Daten über die Overpass-API. Der Server verlangt einen User-Agent, sonst antwortet er mit 406.
 * Der User-Agent enthält bewusst keine persönlichen Angaben.
 */
object OverpassClient {
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /** Gibt die Antwort als Text zurück oder wirft die Ausnahme des letzten Servers. */
    fun fetch(query: String): String {
        var last: Exception? = null
        for (endpoint in ENDPOINTS) {
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.doOutput = true
                conn.setRequestProperty("User-Agent", "PWDR/0.1 (private hobby project)")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                if (conn.responseCode != 200) throw java.io.IOException("Overpass ${conn.responseCode}")
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("Kein Overpass-Server")
    }
}
