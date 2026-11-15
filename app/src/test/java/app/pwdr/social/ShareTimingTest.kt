package app.pwdr.social

import app.pwdr.tracking.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTimingTest {

    @Test
    fun intervalFollowsThePlan() {
        assertEquals(3_000L, ShareTiming.intervalMs(Activity.DOWNHILL, null, 0))
        assertEquals(3_000L, ShareTiming.intervalMs(Activity.DOWNHILL, 100.0, 0))
        assertEquals(5_000L, ShareTiming.intervalMs(Activity.LIFT, 300.0, 0))
        assertEquals(5_000L, ShareTiming.intervalMs(Activity.PAUSE, 499.0, 30_000))
        assertEquals(15_000L, ShareTiming.intervalMs(Activity.LIFT, 800.0, 0))
        assertEquals(15_000L, ShareTiming.intervalMs(Activity.PAUSE, null, 0))
        assertEquals(15_000L, ShareTiming.intervalMs(Activity.LIFT, 500.0, 0))
    }

    @Test
    fun longStandstillSlowsDownEvenWithFriendNearby() {
        assertEquals(30_000L, ShareTiming.intervalMs(Activity.PAUSE, 100.0, 2 * 60_000L + 1))
        assertEquals(5_000L, ShareTiming.intervalMs(Activity.PAUSE, 100.0, 2 * 60_000L))
    }

    @Test
    fun downhillAlwaysSendsFast() {
        // Auch wenn die Position kurz konstant war (Fehlalarm Stillstand), Abfahrt hat Vorrang.
        assertEquals(3_000L, ShareTiming.intervalMs(Activity.DOWNHILL, null, 10 * 60_000L))
    }

    @Test
    fun dueWhenNeverSentOrIntervalPassed() {
        assertTrue(ShareTiming.due(null, 1_000, 3_000))
        assertFalse(ShareTiming.due(1_000, 3_999, 3_000))
        assertTrue(ShareTiming.due(1_000, 4_000, 3_000))
    }

    @Test
    fun freshnessThresholds() {
        val now = 1_000_000L
        assertEquals(Freshness.FRESH, FriendFreshness.of(now - 59_999, now))
        assertEquals(Freshness.STALE, FriendFreshness.of(now - 60_000, now))
        assertEquals(Freshness.STALE, FriendFreshness.of(now - 299_999, now))
        assertEquals(Freshness.HIDDEN, FriendFreshness.of(now - 300_000, now))
    }

    @Test
    fun announcementsIgnoreOldPositions() {
        val now = 1_000_000L
        assertTrue(FriendFreshness.usableForAnnouncement(now - 30_000, now))
        assertFalse(FriendFreshness.usableForAnnouncement(now - 30_001, now))
    }

    @Test
    fun ageLabels() {
        val now = 1_000_000L
        assertEquals("gerade eben", FriendFreshness.ageLabel(now - 5_000, now))
        assertEquals("vor 40 s", FriendFreshness.ageLabel(now - 40_000, now))
        assertEquals("vor 2 min", FriendFreshness.ageLabel(now - 125_000, now))
        assertEquals("gerade eben", FriendFreshness.ageLabel(now + 3_000, now)) // Uhr leicht vorgehend
    }
}
