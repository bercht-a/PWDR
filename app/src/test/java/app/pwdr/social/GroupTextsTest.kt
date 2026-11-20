package app.pwdr.social

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTextsTest {
    private val zone = ZoneOffset.UTC
    private val now = java.time.OffsetDateTime.parse("2026-11-20T12:00:00Z").toInstant().toEpochMilli()

    private fun group(expires: String?) = GroupRow("g", "Arosa", "owner", 3, 0, "Greta", expires, true, true)
    private fun member(visible: Boolean = false, share: Boolean = true, joined: String? = null) =
        GroupMemberRow("m", "Hugo", null, "#fff", "member", joined, share, visible)

    @Test fun durationOptionsOfferTheRecommendedSevenDaysThirdAndUnlimitedLast() {
        val o = GroupTexts.durationOptions(LocalDate.of(2026, 11, 20))
        assertEquals(7, o[GroupTexts.DEFAULT_DURATION_INDEX].days)
        assertTrue(o[GroupTexts.DEFAULT_DURATION_INDEX].label.contains("empfohlen"))
        assertNull(o.last().days)
        assertEquals(listOf(1, 3, 7), o.take(3).map { it.days })
    }

    @Test fun seasonEndAcrossTheYearAndAfterTheSeason() {
        // 20.11.2026 bis 30.4.2027
        assertEquals(161, GroupTexts.daysToSeasonEnd(LocalDate.of(2026, 11, 20)))
        // Am 30. April selbst bleibt mindestens ein Tag
        assertEquals(1, GroupTexts.daysToSeasonEnd(LocalDate.of(2027, 4, 30)))
        // Im Sommer gilt der nächste April, nie mehr als 366 Tage
        val summer = GroupTexts.daysToSeasonEnd(LocalDate.of(2027, 5, 1))
        assertTrue(summer in 300..366)
    }

    @Test fun memberCountText() {
        assertEquals("1 Mitglied", GroupTexts.memberCount(1))
        assertEquals("5 Mitglieder", GroupTexts.memberCount(5))
    }

    @Test fun expiryTexts() {
        assertEquals("unbegrenzt", GroupTexts.expiry(group(null), now, zone))
        assertEquals("abgelaufen", GroupTexts.expiry(group("2026-11-19T12:00:00Z"), now, zone))
        assertEquals("läuft in weniger als 1 Std. ab", GroupTexts.expiry(group("2026-11-20T12:30:00Z"), now, zone))
        assertEquals("noch 5 Std. (bis 20.11.)", GroupTexts.expiry(group("2026-11-20T17:30:00Z"), now, zone))
        assertEquals("noch 1 Tag (bis 21.11.)", GroupTexts.expiry(group("2026-11-21T13:00:00Z"), now, zone))
        assertEquals("noch 7 Tage (bis 27.11.)", GroupTexts.expiry(group("2026-11-27T12:30:00Z"), now, zone))
    }

    @Test fun newMemberWithinTwentyFourHours() {
        assertTrue(GroupTexts.isNew("2026-11-20T10:00:00Z", now))
        assertTrue(GroupTexts.isNew("2026-11-19T12:30:00Z", now))
        assertFalse(GroupTexts.isNew("2026-11-19T11:00:00Z", now))
        assertFalse("Zukunft ist nicht neu", GroupTexts.isNew("2026-11-21T10:00:00Z", now))
        assertFalse(GroupTexts.isNew(null, now))
        assertFalse(GroupTexts.isNew("kaputt", now))
    }

    @Test fun memberStatusTexts() {
        assertEquals("Du", GroupTexts.memberStatus(member(), isMe = true))
        assertEquals("Du, teilst in dieser Gruppe nicht", GroupTexts.memberStatus(member(share = false), isMe = true))
        assertEquals("auf der Karte", GroupTexts.memberStatus(member(visible = true), isMe = false))
        assertEquals("teilt in dieser Gruppe nicht", GroupTexts.memberStatus(member(share = false), isMe = false))
        assertEquals("zeichnet gerade nicht auf", GroupTexts.memberStatus(member(), isMe = false))
    }

    @Test fun viewerLinesNameTheWayTheySeeMe() {
        val lines = GroupTexts.viewerLines(
            listOf(
                ViewerRow("2", "Hugo", "#fff", true, listOf("Arosa", "Saison")),
                ViewerRow("5", "Kai", "#fff", true, emptyList()),
                ViewerRow("6", "lena", "#fff", false, listOf("Arosa")),
            ),
        )
        assertEquals(listOf("Hugo (Freund, Arosa, Saison)", "Kai (Freund)", "lena (Arosa)"), lines)
        assertTrue(GroupTexts.viewerLines(emptyList()).isEmpty())
    }

    @Test fun shareTextContainsCodeAndLink() {
        val t = GroupTexts.inviteShareText("ABC234", "Wochenende Arosa", 10)
        assertTrue(t.contains("ABC234"))
        assertTrue(t.contains("pwdr://group/ABC234"))
        assertTrue(t.contains("Wochenende Arosa"))
        assertTrue(t.contains("24 Stunden"))
    }

    @Test fun joinNoticeStatesVisibilityAndWayOut() {
        assertTrue(GroupTexts.JOIN_NOTICE.contains("sehen deinen Standort"))
        assertTrue(GroupTexts.JOIN_NOTICE.contains("verlassen"))
    }
}
