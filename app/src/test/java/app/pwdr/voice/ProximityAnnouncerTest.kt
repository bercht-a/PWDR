package app.pwdr.voice

import app.pwdr.tracking.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityAnnouncerTest {

    private val lat0 = 46.49
    private val lon0 = 7.56
    private val own = OwnPosition(lat0, lon0, 2000.0, Activity.DOWNHILL)

    /** Freund [meters] nördlich der eigenen Position. */
    private fun friend(
        id: String = "m", name: String = "Marco", meters: Double, alt: Double? = 2000.0,
        state: String? = "downhill", updatedMs: Long,
    ) = NearbyFriend(id, name, lat0 + meters / 111_320.0, lon0, alt, state, updatedMs)

    private fun run(
        announcer: ProximityAnnouncer, distances: List<Double>, startMs: Long = 1_000_000, stepMs: Long = 5_000,
        muted: Set<String> = emptySet(), ownPos: OwnPosition = own,
    ): List<Pair<Long, ProximityEvent>> {
        val out = ArrayList<Pair<Long, ProximityEvent>>()
        distances.forEachIndexed { i, d ->
            val now = startMs + i * stepMs
            announcer.onUpdate(ownPos, listOf(friend(meters = d, updatedMs = now)), muted, now).forEach { out.add(now to it) }
        }
        return out
    }

    @Test
    fun approachAnnouncesEachLevelOnce() {
        // Von 600 m auf 0 m in 20-m-Schritten, alle 5 s, mit 70 s Wartezeit zwischen den Stufen
        val a = ProximityAnnouncer()
        val d = (600 downTo 0 step 20).map { it.toDouble() }
        val events = run(a, d, stepMs = 25_000)
        assertEquals("Ansagen: $events", 3, events.size)
        assertFalse(events[0].second.safety)
        assertFalse(events[1].second.safety)
        assertTrue("engste Stufe ist Sicherheit", events[2].second.safety)
        assertEquals(listOf("Marco"), events[0].second.names)
        assertTrue(events[0].second.distanceM!! < 300 && events[0].second.distanceM!! > 270)
        assertTrue(events[1].second.distanceM!! < 150)
        assertTrue(events[2].second.distanceM!! < 50)
    }

    @Test
    fun nothingBeyondThreeHundredMeters() {
        val a = ProximityAnnouncer()
        assertTrue(run(a, listOf(800.0, 500.0, 320.0, 301.0)).isEmpty())
    }

    @Test
    fun wobbleAroundTheBoundaryAnnouncesOnlyOnce() {
        // 300 m +/- 15 m, sehr lange: durch Hysterese und Wartezeit nur eine Ansage
        val a = ProximityAnnouncer()
        val d = (0 until 200).map { if (it % 2 == 0) 290.0 else 315.0 }
        assertEquals(1, run(a, d).size)
    }

    @Test
    fun cooldownDelaysSecondLevel() {
        // 280 m, dann 5 s später 140 m: die zweite Stufe kommt erst nach 60 s, solange der Freund noch in der Stufe ist
        val a = ProximityAnnouncer()
        val events = run(a, listOf(280.0) + List(20) { 140.0 }, stepMs = 5_000)
        assertEquals(2, events.size)
        assertTrue("Abstand der Ansagen: ${events[1].first - events[0].first}", events[1].first - events[0].first >= 60_000)
    }

    @Test
    fun levelIsFreedOnlyBeyondOneAndAHalfTimesTheDistance() {
        val a = ProximityAnnouncer()
        val now = 1_000_000L
        // 140 m: Stufe 150 angesagt (Stufe 300 wird gleichzeitig übersprungen: es zählt die engste erreichte Stufe)
        assertEquals(1, run(a, listOf(140.0), startMs = now).size)
        // Weg auf 200 m (unter 1,5 x 150 = 225): Stufe bleibt belegt. Zurück auf 140 m nach langer Zeit: keine Ansage
        val later = now + 5 * 60_000
        assertTrue(run(a, listOf(200.0, 140.0), startMs = later).isEmpty())
        // Weg auf 260 m (über 225, aber unter 450): Stufe 150 frei, Stufe 300 noch belegt. Wieder 140 m: Ansage
        val much = later + 5 * 60_000
        assertEquals(1, run(a, listOf(260.0, 140.0), startMs = much).size)
    }

    @Test
    fun goingFarAwayAndComingBackAnnouncesAgain() {
        val a = ProximityAnnouncer()
        val now = 1_000_000L
        assertEquals(1, run(a, listOf(280.0), startMs = now).size)
        // Weit weg (über 450 m), später wieder 280 m
        val later = now + 3 * 60_000
        assertEquals(1, run(a, listOf(600.0, 280.0), startMs = later).size)
    }

    @Test
    fun staleFriendPositionIsIgnored() {
        val a = ProximityAnnouncer()
        val now = 1_000_000L
        val old = friend(meters = 40.0, updatedMs = now - 31_000)
        assertTrue(a.onUpdate(own, listOf(old), emptySet(), now).isEmpty())
        val fresh = friend(meters = 40.0, updatedMs = now - 29_000)
        assertEquals(1, a.onUpdate(own, listOf(fresh), emptySet(), now).size)
    }

    @Test
    fun heightDifferenceOverThirtyMetersIsNotNear() {
        val a = ProximityAnnouncer()
        val now = 1_000_000L
        // Freund 20 m entfernt, aber 60 m höher (Gondel über der Piste)
        assertTrue(a.onUpdate(own, listOf(friend(meters = 20.0, alt = 2060.0, updatedMs = now)), emptySet(), now).isEmpty())
        assertEquals(1, a.onUpdate(own, listOf(friend(meters = 20.0, alt = 2025.0, updatedMs = now)), emptySet(), now + 1).size)
    }

    @Test
    fun missingAltitudeDoesNotBlock() {
        val a = ProximityAnnouncer()
        assertEquals(1, a.onUpdate(own.copy(alt = null), listOf(friend(meters = 100.0, alt = null, updatedMs = 5)), emptySet(), 10).size)
    }

    @Test
    fun bothInLiftGivesNoAnnouncement() {
        val a = ProximityAnnouncer()
        val now = 1_000_000L
        val inLift = own.copy(activity = Activity.LIFT)
        assertTrue(a.onUpdate(inLift, listOf(friend(meters = 10.0, state = "lift", updatedMs = now)), emptySet(), now).isEmpty())
        // Nur einer im Lift: Ansage kommt
        assertEquals(1, a.onUpdate(inLift, listOf(friend(meters = 10.0, state = "downhill", updatedMs = now)), emptySet(), now + 1).size)
    }

    @Test
    fun mutedFriendIsNeverAnnounced() {
        val a = ProximityAnnouncer()
        assertTrue(run(a, listOf(250.0, 100.0, 30.0), muted = setOf("m")).isEmpty())
    }

    @Test
    fun severalFriendsAreBundledIntoOneAnnouncement() {
        val a = ProximityAnnouncer()
        val now = 1_000_000L
        val friends = listOf(
            friend(id = "m", name = "Marco", meters = 120.0, updatedMs = now),
            friend(id = "l", name = "Lena", meters = 200.0, updatedMs = now),
            friend(id = "t", name = "Tom", meters = 700.0, updatedMs = now),
        )
        val events = a.onUpdate(own, friends, emptySet(), now)
        assertEquals(1, events.size)
        assertEquals(listOf("Marco", "Lena"), events[0].names)
        assertEquals(null, events[0].distanceM)
        assertFalse(events[0].safety)
    }

    @Test
    fun bundleWithSafetyLevelIsSafety() {
        val a = ProximityAnnouncer()
        val now = 1_000_000L
        val friends = listOf(
            friend(id = "m", name = "Marco", meters = 40.0, updatedMs = now),
            friend(id = "l", name = "Lena", meters = 250.0, updatedMs = now),
        )
        assertTrue(a.onUpdate(own, friends, emptySet(), now)[0].safety)
    }

    @Test
    fun friendWhoStopsSharingIsForgottenAndAnnouncedAgain() {
        val a = ProximityAnnouncer()
        val now = 1_000_000L
        assertEquals(1, a.onUpdate(own, listOf(friend(meters = 100.0, updatedMs = now)), emptySet(), now).size)
        // Verschwindet aus der Liste (Freigabe beendet)
        a.onUpdate(own, emptyList(), emptySet(), now + 10_000)
        // Kommt nach der Wartezeit wieder, gleiche Entfernung: wird erneut angesagt
        assertEquals(1, a.onUpdate(own, listOf(friend(meters = 100.0, updatedMs = now + 70_000)), emptySet(), now + 70_000).size)
    }

    @Test
    fun textsMatchThePlanExamples() {
        assertEquals("Marco ist 150 Meter entfernt.", SpeechText.friendNear("Marco", 148.0, false))
        assertEquals("Achtung, Marco ist 50 Meter entfernt.", SpeechText.friendNear("Marco", 48.0, true))
        assertEquals("Marco und Lena sind in der Nähe.", SpeechText.friendsNear(listOf("Marco", "Lena"), false))
        assertEquals("Marco, Lena und Tom sind in der Nähe.", SpeechText.friendsNear(listOf("Marco", "Lena", "Tom"), false))
        assertEquals("Achtung, Marco und Lena sind in der Nähe.", SpeechText.friendsNear(listOf("Marco", "Lena"), true))
    }

    @Test
    fun nearbyFromUsesSpokenNameAndSkipsBlockedAndIncomplete() {
        val now = java.time.Instant.parse("2026-11-15T12:00:00Z")
        val ts = now.toString()
        val friends = listOf(
            app.pwdr.social.Friend("a", "Anna", "Anna mit A", "#FF0000", "accepted"),
            app.pwdr.social.Friend("b", "Ben", "  ", "#00FF00", "accepted"),
            app.pwdr.social.Friend("c", "Cleo", null, "#0000FF", "blocked"),
            app.pwdr.social.Friend("d", "Dan", null, "#0000FF", "accepted"),
        )
        val live = mapOf(
            "a" to app.pwdr.social.LiveRow("a", 46.4, 7.7, 2000.0, null, null, null, "lift", ts),
            "b" to app.pwdr.social.LiveRow("b", 46.4, 7.7, null, null, null, null, null, ts),
            "c" to app.pwdr.social.LiveRow("c", 46.4, 7.7, null, null, null, null, null, ts),
            "d" to app.pwdr.social.LiveRow("d", null, null, null, null, null, null, null, ts),
            "x" to app.pwdr.social.LiveRow("x", 46.4, 7.7, null, null, null, null, null, ts),
        )
        val out = ProximityAnnouncer.nearbyFrom(friends, live).associateBy { it.id }
        assertEquals(setOf("a", "b"), out.keys)
        assertEquals("Anna mit A", out.getValue("a").name)
        assertEquals("Ben", out.getValue("b").name)
        assertEquals("lift", out.getValue("a").state)
        assertEquals(now.toEpochMilli(), out.getValue("a").updatedMs)
    }
}
