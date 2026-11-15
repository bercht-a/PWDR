package app.pwdr.voice

import app.pwdr.tracking.LiveRecorder
import app.pwdr.tracking.TrackSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLogicTest {

    // ---- Texte ----

    @Test
    fun textsAreGermanAndRounded() {
        assertEquals("54 Kilometer pro Stunde.", SpeechText.speed(54))
        assertEquals("Aktuell 54, Höchstwert 71.", SpeechText.onDemand(54, 71))
        assertEquals("Neuer Rekord: 71 Kilometer pro Stunde.", SpeechText.record(71))
        assertEquals("Abfahrt beendet. 2,1 Kilometer, 412 Höhenmeter, Höchstwert 68.", SpeechText.runEnd(2_130.0, 412.0, 68))
        assertEquals("Eine Abfahrt, 300 Höhenmeter, Höchstwert 50.", SpeechText.daySummary(1, 300.0, 50))
        assertEquals("12 Abfahrten, 4500 Höhenmeter, Höchstwert 77.", SpeechText.daySummary(12, 4500.0, 77))
    }

    @Test
    fun distanceRounding() {
        assertEquals("130 Meter", SpeechText.distance(133.0))
        assertEquals("450 Meter", SpeechText.distance(437.0))
        assertEquals("1,0 Kilometer", SpeechText.distance(980.0 + 30))
        assertEquals("2,1 Kilometer", SpeechText.distance(2_130.0))
    }

    // ---- Warteschlange ----

    private fun ann(kind: Kind, p: Priority, maxAge: Long = 10_000, text: String = kind.name) =
        Announcement(kind, p, maxAge) { text }

    @Test
    fun higherPriorityFirstThenOldest() {
        val q = SpeechQueue(minGapMs = 0)
        q.offer(ann(Kind.RUN_END, Priority.LOW), 0)
        q.offer(ann(Kind.RECORD, Priority.MEDIUM), 10)
        q.offer(ann(Kind.DAY_SUMMARY, Priority.HIGH), 20)
        assertEquals(Kind.DAY_SUMMARY, q.next(30)!!.kind)
        assertEquals(Kind.RECORD, q.next(30)!!.kind)
        assertEquals(Kind.RUN_END, q.next(30)!!.kind)
        assertNull(q.next(30))
    }

    @Test
    fun staleAnnouncementsAreDropped() {
        val q = SpeechQueue(minGapMs = 0)
        q.offer(ann(Kind.SPEED, Priority.LOW, maxAge = 3_000), 0)
        assertNull(q.next(3_500))
        assertEquals(0, q.size)
    }

    @Test
    fun onlyNewestSpeedWaits() {
        val q = SpeechQueue(minGapMs = 0)
        q.offer(ann(Kind.SPEED, Priority.LOW, text = "alt"), 0)
        q.offer(ann(Kind.SPEED, Priority.LOW, text = "neu"), 100)
        assertEquals(1, q.size)
        assertEquals("neu", q.next(200)!!.text())
    }

    @Test
    fun minimumGapAppliesExceptForHighPriority() {
        val q = SpeechQueue(minGapMs = 2_000)
        q.onSpoken(1_000)
        q.offer(ann(Kind.RUN_END, Priority.LOW), 1_100)
        assertNull("zu früh", q.next(2_000))
        assertNotNull(q.next(3_100))
        q.onSpoken(3_100)
        q.offer(ann(Kind.ON_DEMAND, Priority.HIGH), 3_200)
        assertNotNull("hohe Priorität ignoriert den Abstand", q.next(3_300))
    }

    @Test
    fun highPriorityInterruptsLowOnly() {
        val q = SpeechQueue(minGapMs = 0)
        q.offer(ann(Kind.ON_DEMAND, Priority.HIGH), 0)
        assertTrue(q.shouldInterrupt(Priority.LOW, 10))
        assertTrue(!q.shouldInterrupt(Priority.HIGH, 10))
    }

    @Test
    fun speedTextIsEvaluatedWhenSpoken() {
        var kmh = 40
        val q = SpeechQueue(minGapMs = 0)
        q.offer(Announcement(Kind.SPEED, Priority.LOW, 5_000) { SpeechText.speed(kmh) }, 0)
        kmh = 55
        assertEquals("55 Kilometer pro Stunde.", q.next(100)!!.text())
    }

    // ---- Planer, gefüttert durch die echte Live-Erkennung ----

    private data class Run(val announcements: List<Announcement>)

    private fun drive(sim: TrackSimulator, settings: VoiceSettings, dayMaxMs: Double = 0.0): List<Announcement> {
        val recorder = LiveRecorder()
        val planner = AnnouncementPlanner(dayMaxMs)
        val out = ArrayList<Announcement>()
        for (s in sim.samples) out.addAll(planner.onState(recorder.onSample(s), s.timeMs, settings))
        return out
    }

    private fun twoRuns() = TrackSimulator()
        .pause(30).lift(240).pause(40).downhill(180).pause(40)
        .lift(240).pause(40).downhill(200, hSpeed = 14.0, vSpeed = -4.0).pause(60)

    @Test
    fun speedIntervalAnnouncesOnlyDuringDownhill() {
        val a = drive(twoRuns(), VoiceSettings(speedIntervalSec = 20, speedMinChangeKmh = 0, recordEnabled = false, runEndEnabled = false))
        val speed = a.filter { it.kind == Kind.SPEED }
        // Zwei Abfahrten à 180/200 s, alle 20 s, aber erst nach Erkennung (ca. 5 s Verzögerung).
        assertTrue("Speed-Ansagen: ${speed.size}", speed.size in 16..24)
        assertTrue(speed.all { it.priority == Priority.LOW })
    }

    @Test
    fun noSpeedAnnouncementsWhenIntervalOff() {
        val a = drive(twoRuns(), VoiceSettings(speedIntervalSec = 0, recordEnabled = false, runEndEnabled = false))
        assertTrue(a.none { it.kind == Kind.SPEED })
    }

    @Test
    fun steadySpeedIsNotAnnouncedRepeatedly() {
        // Gleichmäßiges Tempo: nach der ersten Ansage keine weitere, solange sich der Wert nicht um 5 km/h ändert.
        val sim = TrackSimulator().pause(30).lift(240).pause(40).downhill(180)
        val a = drive(sim, VoiceSettings(speedIntervalSec = 10, speedMinChangeKmh = 5, recordEnabled = false, runEndEnabled = false))
        val speed = a.filter { it.kind == Kind.SPEED }
        assertTrue("zu viele gleiche Ansagen: ${speed.size}", speed.size in 1..3)
    }

    @Test
    fun runEndIsAnnouncedWithSummaryAfterEachRun() {
        val a = drive(twoRuns(), VoiceSettings(recordEnabled = false))
        val ends = a.filter { it.kind == Kind.RUN_END }
        assertEquals(2, ends.size)
        val text = ends[0].text()
        assertTrue(text, text.startsWith("Abfahrt beendet."))
        // 180 s mit -3,6 m/s: ca. 650 Höhenmeter (Simulator), Distanz ca. 1,8 km.
        assertTrue(text, text.contains("Höhenmeter") && text.contains("Kilometer"))
    }

    @Test
    fun runEndCanBeDisabled() {
        val a = drive(twoRuns(), VoiceSettings(runEndEnabled = false, recordEnabled = false))
        assertTrue(a.none { it.kind == Kind.RUN_END })
    }

    @Test
    fun newRecordOnlyWhenThereIsAnEarlierMaxAndOncePerRun() {
        // Zweite Abfahrt ist schneller (14 m/s gegen 10 m/s): Rekord, einmalig.
        val a = drive(twoRuns(), VoiceSettings(runEndEnabled = false))
        val records = a.filter { it.kind == Kind.RECORD }
        assertEquals(1, records.size)
        assertEquals(Priority.MEDIUM, records[0].priority)
        assertTrue(records[0].text().startsWith("Neuer Rekord:"))
    }

    @Test
    fun noRecordOnTheFirstRunOfTheDay() {
        val sim = TrackSimulator().pause(30).lift(240).pause(40).downhill(180).pause(60)
        val a = drive(sim, VoiceSettings(runEndEnabled = false))
        assertTrue(a.none { it.kind == Kind.RECORD })
    }

    @Test
    fun earlierSessionMaxEnablesRecordOnFirstRun() {
        // Früher am Tag schon 30 km/h gefahren (8,3 m/s): die 36 km/h dieser Abfahrt sind ein Rekord.
        val sim = TrackSimulator().pause(30).lift(240).pause(40).downhill(180).pause(60)
        val a = drive(sim, VoiceSettings(runEndEnabled = false), dayMaxMs = 8.3)
        assertEquals(1, a.count { it.kind == Kind.RECORD })
    }
}
