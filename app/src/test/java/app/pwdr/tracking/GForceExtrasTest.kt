package app.pwdr.tracking

import app.pwdr.data.RunEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GForceExtrasTest {
    // ---------------------------------------------------------------- Trageposition
    @Test fun carryPositionDefaultsToPocket() {
        assertEquals(CarryPosition.POCKET, CarryPosition.of(null))
        assertEquals(CarryPosition.POCKET, CarryPosition.of("quatsch"))
        assertEquals(CarryPosition.CHEST, CarryPosition.of("chest"))
    }

    @Test fun smoothingGrowsWithDampingOfPosition() {
        assertTrue(CarryPosition.CHEST.smoothing < CarryPosition.POCKET.smoothing)
        assertTrue(CarryPosition.POCKET.smoothing < CarryPosition.BACKPACK.smoothing)
    }

    @Test fun strongerSmoothingDampsASpikeMore() {
        fun peak(smoothing: Int): Double {
            val t = GForceTracker(smoothing)
            val g = GForceTracker.STANDARD_GRAVITY.toFloat()
            repeat(30) { t.onAccel(0f, 0f, g) }
            t.onAccel(0f, 0f, 6f * g)
            repeat(30) { t.onAccel(0f, 0f, g) }
            return t.drain()!!.max
        }
        assertTrue(peak(CarryPosition.BACKPACK.smoothing) < peak(CarryPosition.CHEST.smoothing))
    }

    // ---------------------------------------------------------------- Stärkste Kurve
    private fun s(t: Long, g: Double?, acc: Float = 5f, lat: Double = 46.0) =
        Sample(t, lat, 7.0, 2000.0, 10.0, acc, null, g, g)

    private val down = listOf(ActivityRange(Activity.DOWNHILL, 0, 10_000))

    @Test fun peakIsHighestGInsideDownhill() {
        val samples = listOf(s(0, 1.2), s(1000, 2.5, lat = 46.5), s(2000, 1.8))
        val p = GForceStats.peakIn(listOf(samples), down)!!
        assertEquals(2.5, p.g, 1e-9)
        assertEquals(46.5, p.lat, 1e-9)
        assertEquals(1000L, p.timeMs)
    }

    @Test fun peakIgnoresLiftAndPause() {
        val samples = listOf(s(1000, 4.0), s(20_000, 2.0))
        val ranges = listOf(ActivityRange(Activity.LIFT, 0, 10_000), ActivityRange(Activity.DOWNHILL, 15_000, 25_000))
        assertEquals(2.0, GForceStats.peakIn(listOf(samples), ranges)!!.g, 1e-9)
    }

    @Test fun peakIgnoresInaccuratePointsAndRest() {
        assertNull(GForceStats.peakIn(listOf(listOf(s(0, 3.0, acc = 80f))), down))
        assertNull(GForceStats.peakIn(listOf(listOf(s(0, 1.05), s(1000, 1.1))), down))
        assertNull(GForceStats.peakIn(listOf(listOf(s(0, null))), down))
    }

    @Test fun peakIsNullWithoutDownhill() {
        assertNull(GForceStats.peakIn(listOf(listOf(s(0, 3.0))), listOf(ActivityRange(Activity.LIFT, 0, 10_000))))
    }

    // ---------------------------------------------------------------- Akkutext
    @Test fun batteryTextShowsUsageAndRate() {
        val t = BatteryText.describe(100, 88, 3 * 3_600_000L + 12 * 60_000L)!!
        assertTrue(t, t.contains("100 → 88 % (12 %) in 3:12 h"))
        assertTrue(t, t.contains("pro Stunde"))
    }

    @Test fun batteryTextShortRecordingHasNoRate() {
        val t = BatteryText.describe(90, 89, 10 * 60_000L)!!
        assertTrue(t, !t.contains("pro Stunde"))
    }

    @Test fun batteryTextChargingAndMissing() {
        assertTrue(BatteryText.describe(50, 70, 3_600_000L)!!.contains("geladen"))
        assertNull(BatteryText.describe(null, 50, 3_600_000L))
        assertNull(BatteryText.describe(50, null, 3_600_000L))
        assertNull(BatteryText.describe(50, 40, 0))
    }

    // ---------------------------------------------------------------- Zeiträume
    private val zone: ZoneId = ZoneOffset.UTC
    private fun ms(date: String) = LocalDate.parse(date).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun run(date: String, vert: Double = 300.0, dist: Double = 2000.0, vmax: Double = 15.0, maxG: Double? = null, avgG: Double? = null, type: String = "DOWNHILL") =
        RunEntity(
            sessionId = 1, type = type, startMs = ms(date), endMs = ms(date) + 120_000, distanceM = dist, vertDownM = vert,
            vertUpM = 0.0, maxSpeedMs = vmax, avgSpeedMs = 8.0, maxG = maxG, avgG = avgG,
        )

    @Test fun periodStartDates() {
        val wed = LocalDate.parse("2026-11-18") // Mittwoch
        assertEquals(LocalDate.parse("2026-11-16"), Period.WEEK.start(wed))
        assertEquals(LocalDate.parse("2026-11-01"), Period.MONTH.start(wed))
        assertEquals(LocalDate.parse("2026-10-01"), Period.SEASON.start(wed))
        assertEquals(LocalDate.parse("2025-10-01"), Period.SEASON.start(LocalDate.parse("2026-03-10")))
        assertEquals(LocalDate.parse("2026-10-01"), Period.SEASON.start(LocalDate.parse("2026-10-01")))
    }

    @Test fun summaryCountsOnlyThePeriod() {
        val today = LocalDate.parse("2026-11-18")
        val runs = listOf(
            run("2026-11-17", maxG = 1.8, avgG = 1.2),
            run("2026-11-17", maxG = 2.4, avgG = 1.4),
            run("2026-11-02", maxG = 3.0, avgG = 1.5),
            run("2026-10-20"),
            run("2026-09-30"), // vor der Saison
        )
        val week = PeriodStats.summarize(runs, Period.WEEK, today, zone)
        assertEquals(1, week.days)
        assertEquals(2, week.runs)
        assertEquals(2.4, week.maxG!!, 1e-9)
        assertEquals(1.3, week.avgG!!, 1e-9)
        val month = PeriodStats.summarize(runs, Period.MONTH, today, zone)
        assertEquals(3, month.runs)
        assertEquals(3.0, month.maxG!!, 1e-9)
        val season = PeriodStats.summarize(runs, Period.SEASON, today, zone)
        assertEquals(4, season.runs)
        assertEquals(3, season.days)
        assertEquals(1200.0, season.vertDownM, 1e-9)
    }

    @Test fun summarySkipsLiftsAndTinyRunsAndHandlesNoG() {
        val today = LocalDate.parse("2026-11-18")
        val runs = listOf(run("2026-11-17", type = "LIFT"), run("2026-11-17", vert = 5.0), run("2026-11-17"))
        val sum = PeriodStats.summarize(runs, Period.WEEK, today, zone)
        assertEquals(1, sum.runs)
        assertNull(sum.maxG)
        assertNull(sum.avgG)
        assertNotNull(sum)
    }

    @Test fun emptyPeriod() {
        val sum = PeriodStats.summarize(emptyList(), Period.WEEK, LocalDate.parse("2026-11-18"), zone)
        assertEquals(0, sum.runs)
        assertEquals(0.0, sum.vmaxMs, 0.0)
    }
}
