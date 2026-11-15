package app.pwdr.social

/** Technischer Stand des Teilens, nur zur Fehlersuche im Tab "Freunde". */
data class ShareDiag(
    /** Ergebnis des Freigabe-Starts, z. B. "keine Freunde" oder ein Fehler. Null = noch nichts versucht. */
    val startNote: String? = null,
    val lastSentMs: Long? = null,
    val sentCount: Int = 0,
    val lastError: String? = null,
    /** Verbindung für Live-Positionen: "verbunden", "verbindet…" oder der Fehler. */
    val realtime: String = "verbindet…",
    /** Eine Position wurde nicht gesendet (z. B. GPS zu ungenau), mit Grund. */
    val skipNote: String? = null,
)

object ShareDiagText {
    /** Zeilen für die Statusanzeige. [visible] = Freunde, die gerade auf der Karte erscheinen, [rows] = erhaltene Zeilen. */
    fun lines(d: ShareDiag, shareActive: Boolean, recording: Boolean, visible: Int, rows: Int, nowMs: Long): List<String> {
        val out = mutableListOf<String>()
        out += when {
            !recording -> "Aufzeichnung: läuft nicht (gesendet wird nur beim Aufzeichnen)"
            shareActive -> "Freigabe: aktiv"
            else -> "Freigabe: nicht aktiv" + (d.startNote?.let { " ($it)" } ?: "")
        }
        out += when {
            d.lastSentMs != null -> "Zuletzt gesendet: ${FriendFreshness.ageLabel(d.lastSentMs, nowMs)} (${d.sentCount}×)"
            recording && shareActive -> "Noch nichts gesendet" + (d.skipNote?.let { " ($it)" } ?: "")
            else -> "Noch nichts gesendet"
        }
        d.lastError?.let { out += "Sendefehler: $it" }
        out += "Live-Verbindung: ${d.realtime}"
        out += "Empfangen: $rows Freunde senden, $visible auf der Karte"
        return out
    }
}
