package app.pwdr.tracking

/** Akku-Verbrauch einer Aufzeichnung als Text, zum Vergleichen mit und ohne G-Force. */
object BatteryText {
    /**
     * "Akku: 100 → 88 % (12 %) in 3:12 h, rund 4 % pro Stunde". Null, wenn Werte fehlen. Stieg der Stand (Handy lud),
     * steht ein Hinweis statt eines Verbrauchs. Der Wert gilt für das ganze Handy, nicht nur für PWDR.
     */
    fun describe(startPct: Int?, endPct: Int?, durationMs: Long): String? {
        if (startPct == null || endPct == null || durationMs <= 0) return null
        if (endPct > startPct) return "Akku: $startPct → $endPct % (Handy wurde geladen, kein Vergleich möglich)"
        val used = startPct - endPct
        val hours = durationMs / 3_600_000.0
        val h = durationMs / 3_600_000
        val m = durationMs / 60_000 % 60
        val time = String.format(java.util.Locale.GERMAN, "%d:%02d h", h, m)
        val base = "Akku: $startPct → $endPct % ($used %) in $time"
        // Unter 20 Minuten sind ganze Prozentschritte zu grob für eine Rate
        return if (hours >= 1.0 / 3) base + String.format(java.util.Locale.GERMAN, ", rund %.1f %% pro Stunde", used / hours) else base
    }
}
