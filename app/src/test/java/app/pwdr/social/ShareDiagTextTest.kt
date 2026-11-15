package app.pwdr.social

import org.junit.Assert.assertTrue
import org.junit.Test

class ShareDiagTextTest {
    private val now = 1_000_000L

    @Test fun notRecording() {
        val l = ShareDiagText.lines(ShareDiag(), false, false, 0, 0, now)
        assertTrue(l.first().contains("läuft nicht"))
    }

    @Test fun shareNotActiveShowsReason() {
        val l = ShareDiagText.lines(ShareDiag(startNote = "keine Freunde ausgewählt"), false, true, 0, 0, now)
        assertTrue(l.first().contains("nicht aktiv (keine Freunde ausgewählt)"))
    }

    @Test fun sentAndError() {
        val d = ShareDiag(lastSentMs = now - 30_000, sentCount = 4, lastError = "no_active_share", realtime = "verbunden")
        val l = ShareDiagText.lines(d, true, true, 1, 2, now)
        assertTrue(l.any { it.contains("vor 30 s") && it.contains("4×") })
        assertTrue(l.any { it.contains("Sendefehler: no_active_share") })
        assertTrue(l.any { it.contains("2 Freunde senden, 1 auf der Karte") })
    }

    @Test fun nothingSentShowsGpsReason() {
        val l = ShareDiagText.lines(ShareDiag(skipNote = "GPS zu ungenau: 80 m"), true, true, 0, 0, now)
        assertTrue(l.any { it.contains("Noch nichts gesendet (GPS zu ungenau: 80 m)") })
    }
}
