package app.pwdr.tracking

import kotlin.math.pow

/**
 * Höhe aus Luftdruck, verankert an der GPS-Höhe.
 *
 * Der Druck liefert Höhenänderungen sehr genau (Rauschen im Dezimeterbereich), aber keine absolute Höhe,
 * weil sich das Wetter ändert (ca. 1 hPa pro Stunde entspricht rund 8 m). Deshalb wird die Differenz
 * zwischen Druckhöhe und GPS-Höhe langsam nachgeführt (Zeitkonstante [OFFSET_TAU_S]): kurzfristig zählt
 * der Druck, langfristig die GPS-Höhe. Schlechte GPS-Fixes ([MAX_GPS_ACCURACY_M]) verschieben den Anker nicht.
 */
class BarometerAltitude {
    private var pressureHpa: Double? = null
    private var offsetM: Double? = null
    private var lastAnchorMs: Long = 0
    private var prevBaro: Double? = null
    private var prevBaroMs: Long = 0

    /** Neuer Sensorwert. Leichte Glättung gegen Sensorrauschen. */
    fun onPressure(hPa: Float) {
        val p = hPa.toDouble()
        pressureHpa = pressureHpa?.let { it + PRESSURE_ALPHA * (p - it) } ?: p
    }

    val hasPressure: Boolean get() = pressureHpa != null

    /**
     * Fusionierte Höhe zum GPS-Fix. Ohne Druckwert oder ohne verwertbaren Anker kommt die GPS-Höhe zurück
     * (bzw. null, wenn es auch keine gibt).
     */
    fun altitude(timeMs: Long, gpsAltM: Double?, gpsAccuracyM: Float): Double? {
        val p = pressureHpa ?: return gpsAltM
        val baro = pressureToAltitude(p)
        // Steigt oder fällt man gerade, hinkt die GPS-Höhe nach: dann den Anker nicht nachführen.
        val dtPrev = (timeMs - prevBaroMs) / 1000.0
        val vertRate = prevBaro?.let { if (dtPrev > 0) kotlin.math.abs(baro - it) / dtPrev else 0.0 } ?: 0.0
        prevBaro = baro
        prevBaroMs = timeMs
        val goodGps = gpsAltM != null && gpsAccuracyM <= MAX_GPS_ACCURACY_M
        val off = offsetM
        if (off == null) {
            // Ohne guten Anker lieber noch keine Barometerhöhe ausgeben.
            if (!goodGps) return gpsAltM
            offsetM = gpsAltM - baro
            lastAnchorMs = timeMs
        } else if (goodGps && vertRate <= MAX_ANCHOR_VERT_RATE_MS) {
            val dt = ((timeMs - lastAnchorMs) / 1000.0).coerceIn(0.0, 30.0)
            val k = dt / (OFFSET_TAU_S + dt)
            offsetM = off + k * ((gpsAltM - baro) - off)
            lastAnchorMs = timeMs
        }
        return baro + offsetM!!
    }

    companion object {
        const val OFFSET_TAU_S = 300.0
        const val MAX_GPS_ACCURACY_M = 20f
        const val MAX_ANCHOR_VERT_RATE_MS = 0.5
        private const val PRESSURE_ALPHA = 0.3
        private const val SEA_LEVEL_HPA = 1013.25

        /** Internationale Höhenformel (wie SensorManager.getAltitude, hier ohne Android für Unit-Tests). */
        fun pressureToAltitude(hPa: Double): Double =
            44330.0 * (1.0 - (hPa / SEA_LEVEL_HPA).pow(1.0 / 5.255))
    }
}
