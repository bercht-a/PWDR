package app.pwdr.map

import android.content.Context
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Winziger Server auf 127.0.0.1, der die Download-Stile der Offline-Karte ausliefert. Die Offline-Schnittstelle von MapLibre
 * holt den Stil nur über http(s), nicht aus dem App-Paket (asset://) oder einer Datei (file://). Der Server ist nur vom Handy
 * selbst erreichbar, liefert ausschliesslich die sechs Stil-Dateien aus assets/offline und läuft nur, solange die App läuft.
 * Eine feste Adresse hält fortsetzbare Downloads gültig, weil die Region die Stil-Adresse speichert.
 */
internal object LocalStyleServer {
    const val PORT = 38641
    private var server: ServerSocket? = null

    /** Adresse des Stils, zum Beispiel "http://127.0.0.1:38641/aerial-slope.json". */
    fun url(base: BaseMap, slope: Boolean): String = "http://127.0.0.1:$PORT/${base.key}${if (slope) "-slope" else ""}.json"

    /** Startet den Server, falls er noch nicht läuft. Liefert false, wenn der Port nicht frei ist. */
    @Synchronized
    fun start(context: Context): Boolean {
        if (server?.isClosed == false) return true
        val appContext = context.applicationContext
        val socket = try {
            ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            return false
        }
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    handle(appContext, client)
                } catch (_: Exception) {
                } finally {
                    try {
                        client.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }, "pwdr-style-server").apply { isDaemon = true }.start()
        return true
    }

    private fun handle(context: Context, client: Socket) {
        client.soTimeout = 5000
        val request = client.getInputStream().bufferedReader().readLine() ?: return
        // "GET /aerial.json HTTP/1.1"
        val path = request.split(' ').getOrNull(1)?.removePrefix("/") ?: return
        val allowed = BaseMap.entries.flatMap { listOf("${it.key}.json", "${it.key}-slope.json") }
        val out = client.getOutputStream()
        if (path !in allowed) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val body = context.assets.open("offline/$path").use { it.readBytes() }
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
    }
}
