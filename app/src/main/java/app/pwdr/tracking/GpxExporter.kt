package app.pwdr.tracking

import java.time.Instant
import java.util.Locale

/**
 * Schreibt Aufzeichnungen als GPX 1.1. Pro Aufzeichnung ein Track-Segment.
 * ele ist die verwendete (ggf. barometrisch korrigierte) Höhe, die rohe GPS-Höhe steht in den Erweiterungen,
 * damit sich die Höhenkorrektur später nachprüfen lässt.
 */
object GpxExporter {

    fun toGpx(name: String, segments: List<List<Sample>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="PWDR" xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:pwdr="https://pwdr.app/gpx/1">""",
        ).append('\n')
        sb.append("  <metadata><name>").append(escape(name)).append("</name></metadata>\n")
        sb.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        for (seg in segments) {
            if (seg.isEmpty()) continue
            sb.append("    <trkseg>\n")
            for (s in seg) {
                sb.append("      <trkpt lat=\"").append(num(s.lat, 7)).append("\" lon=\"").append(num(s.lon, 7)).append("\">")
                sb.append("<ele>").append(num(s.altM, 1)).append("</ele>")
                sb.append("<time>").append(Instant.ofEpochMilli(s.timeMs)).append("</time>")
                sb.append("<extensions>")
                sb.append("<pwdr:speed>").append(num(s.speedMs, 2)).append("</pwdr:speed>")
                sb.append("<pwdr:acc>").append(num(s.accuracyM.toDouble(), 1)).append("</pwdr:acc>")
                if (s.gpsAltM != null) sb.append("<pwdr:gpsele>").append(num(s.gpsAltM, 1)).append("</pwdr:gpsele>")
                sb.append("</extensions></trkpt>\n")
            }
            sb.append("    </trkseg>\n")
        }
        sb.append("  </trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun num(v: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", v)

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
