package app.pwdr.social

import app.pwdr.tracking.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSchedulerTest {

    private val lat = 46.49
    private val lon = 7.56

    @Test
    fun firstFixIsSentImmediately() {
        assertTrue(ShareScheduler().onFix(lat, lon, Activity.PAUSE, null, 1_000))
    }

    @Test
    fun downhillSendsEveryThreeSeconds() {
        val s = ShareScheduler()
        var sent = 0
        // 30 s Abfahrt mit 1-Hz-Fixes und Bewegung: etwa alle 3 s
        for (t in 0 until 30) if (s.onFix(lat + t * 0.0001, lon, Activity.DOWNHILL, null, t * 1000L)) sent++
        assertEquals(10, sent)
    }

    @Test
    fun liftWithoutFriendNearSendsEveryFifteenSeconds() {
        val s = ShareScheduler()
        var sent = 0
        for (t in 0 until 60) if (s.onFix(lat + t * 0.00005, lon, Activity.LIFT, 2_000.0, t * 1000L)) sent++
        assertEquals(4, sent)
    }

    @Test
    fun friendNearbySpeedsUpToFiveSeconds() {
        val s = ShareScheduler()
        var sent = 0
        for (t in 0 until 30) if (s.onFix(lat + t * 0.00005, lon, Activity.LIFT, 200.0, t * 1000L)) sent++
        assertEquals(6, sent)
    }

    @Test
    fun longStandstillSlowsToThirtySeconds() {
        val s = ShareScheduler()
        val sentAt = ArrayList<Long>()
        // 5 Minuten am selben Ort (nur GPS-Rauschen unter 15 m), Freund in der Nähe
        for (t in 0 until 300) {
            val jitter = (t % 3) * 0.00001
            if (s.onFix(lat + jitter, lon, Activity.PAUSE, 100.0, t * 1000L)) sentAt.add(t * 1000L)
        }
        // Erst alle 5 s bis 2 min Stillstand, danach alle 30 s
        val after = sentAt.filter { it > 130_000 }
        assertTrue("Abstände nach Stillstand: $after", after.zipWithNext().all { (a, b) -> b - a >= 30_000 })
        assertTrue(sentAt.size < 300 / 5)
    }

    @Test
    fun movingAgainResetsStillness() {
        val s = ShareScheduler()
        for (t in 0 until 200) s.onFix(lat, lon, Activity.PAUSE, 100.0, t * 1000L)
        // 100 m weiter: nicht mehr still, Takt wieder 5 s
        var sent = 0
        for (t in 200 until 230) if (s.onFix(lat + 0.001 + t * 0.0001, lon, Activity.PAUSE, 100.0, t * 1000L)) sent++
        assertTrue("gesendet: $sent", sent >= 5)
    }

    @Test
    fun resetSendsRightAway() {
        val s = ShareScheduler()
        s.onFix(lat, lon, Activity.PAUSE, null, 0)
        assertFalse(s.onFix(lat, lon, Activity.PAUSE, null, 1_000))
        s.reset()
        assertTrue(s.onFix(lat, lon, Activity.PAUSE, null, 2_000))
    }

    @Test
    fun activityMapping() {
        assertEquals("downhill", Activity.DOWNHILL.toShareState())
        assertEquals("lift", Activity.LIFT.toShareState())
        assertEquals("pause", Activity.PAUSE.toShareState())
    }

    @Test
    fun parsesPostgresTimestamps() {
        val z = parseInstantMs("2026-11-15T10:11:12Z")
        val offset = parseInstantMs("2026-11-15T10:11:12.123456+00:00")
        assertEquals(1_794_737_472_000L / 1000 * 1000, z / 1000 * 1000 + 0) // gleiche Sekunde
        assertEquals(z + 123, offset)
    }
}
