package app.pwdr.voice

import java.util.Locale

/**
 * Sprechtexte auf Deutsch (PLAN.md 5b, "Texte natürlich halten"): Einheiten ausgeschrieben, kurze Sätze,
 * Dezimalkomma. Zahlen werden als Ziffern übergeben, die deutsche Stimme liest sie vor.
 */
object SpeechText {
    private val DE = Locale.GERMAN

    fun kmh(speedMs: Double): Int = Math.round(speedMs * 3.6).toInt()

    fun speed(kmh: Int) = "$kmh Kilometer pro Stunde."

    fun record(kmh: Int) = "Neuer Rekord: $kmh Kilometer pro Stunde."

    fun onDemand(currentKmh: Int, maxKmh: Int) = "Aktuell $currentKmh, Höchstwert $maxKmh."

    fun runEnd(distanceM: Double, vertDownM: Double, maxKmh: Int) =
        "Abfahrt beendet. ${distance(distanceM)}, ${Math.round(vertDownM)} Höhenmeter, Höchstwert $maxKmh."

    fun daySummary(runs: Int, vertDownM: Double, maxKmh: Int): String {
        val runText = if (runs == 1) "Eine Abfahrt" else "$runs Abfahrten"
        return "$runText, ${Math.round(vertDownM)} Höhenmeter, Höchstwert $maxKmh."
    }

    /** "Marco ist 150 Meter entfernt." Bei der engsten Stufe mit "Achtung, " davor. */
    fun friendNear(name: String, distanceM: Double, safety: Boolean): String {
        val text = "$name ist ${distance(distanceM)} entfernt."
        return if (safety) "Achtung, $text" else text
    }

    /** "Marco und Lena sind in der Nähe." und "Marco, Lena und Tom sind in der Nähe." */
    fun friendsNear(names: List<String>, safety: Boolean): String {
        val list = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} und ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " und " + names.last()
        }
        val verb = if (names.size == 1) "ist" else "sind"
        val text = "$list $verb in der Nähe."
        return if (safety) "Achtung, $text" else text
    }

    /** Unter 200 m auf 10 m, bis 1 km auf 50 m, darüber Kilometer mit einer Nachkommastelle. */
    fun distance(m: Double): String = when {
        m < 200 -> "${(Math.round(m / 10.0) * 10).toInt()} Meter"
        m < 1000 -> "${(Math.round(m / 50.0) * 50).toInt()} Meter"
        else -> String.format(DE, "%.1f Kilometer", m / 1000.0)
    }
}
