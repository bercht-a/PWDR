package app.pwdr.resort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ResortDataTest {

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/resort/$name")!!.bufferedReader(Charsets.UTF_8).readText()

    // ---- Wetter (echte Antwort von Open-Meteo vom 15.11.2026)

    @Test
    fun parsesRealOpenMeteoResponse() {
        val w = WeatherApi.parse(fixture("open-meteo.json"), 2100)
        assertEquals(2100, w.elevationM)
        val now = w.now!!
        assertEquals(3.2, now.tempC!!, 0.01)
        assertEquals(34.9, now.gustKmh!!, 0.01)
        assertEquals(76, now.cloudPct)
        assertEquals(2, now.code)
        assertEquals(5, w.days.size)
        assertEquals(java.time.LocalDate.parse("2026-11-15"), w.days[0].date)
        assertEquals(4.1, w.days[0].tMaxC!!, 0.01)
        assertEquals(15.19, w.days[4].snowSumCm!!, 0.01)
        assertTrue("Stunden: ${w.hours.size}", w.hours.size >= 120)
        assertEquals(2470.0, w.hours[0].freezingLevelM!!, 0.01)
    }

    @Test
    fun upcomingPicksEveryThirdHourFromNow() {
        val w = WeatherApi.parse(fixture("open-meteo.json"), 2100)
        val from = LocalDateTime.parse("2026-11-15T11:30:00")
        val up = WeatherApi.upcoming(w.hours, from, stepHours = 3, count = 4)
        assertEquals(listOf(11, 14, 17, 20), up.map { it.time.hour })
    }

    @Test
    fun weatherUrlUsesMeteoSwissModelAndElevation() {
        val u = WeatherApi.url(WeatherPoint("Berg", 46.4256, 7.7582, 2700))
        assertTrue(u.contains("elevation=2700"))
        assertTrue(u.contains("models=meteoswiss_icon_seamless"))
        assertTrue(u.contains("timezone=Europe%2FZurich"))
    }

    @Test
    fun weatherCodesAreGerman() {
        assertEquals("Klar", WeatherApi.describe(0))
        assertEquals("Schneefall", WeatherApi.describe(73))
        assertEquals("Gewitter", WeatherApi.describe(95))
        assertEquals("–", WeatherApi.describe(null))
    }

    @Test
    fun emptyOrMissingSectionsDoNotCrash() {
        val w = WeatherApi.parse("""{"latitude":1.0}""", 1000)
        assertNull(w.now)
        assertTrue(w.hours.isEmpty() && w.days.isEmpty())
    }

    // ---- Schnee (echte IMIS-Antwort Gandegg GAN2 vom 15.11.2026)

    @Test
    fun parsesRealImisResponse() {
        val s = SnowApi.parse(fixture("imis-gan2.json"))
        assertEquals(47, s.size)
        assertTrue(s.zipWithNext().all { (a, b) -> b.timeMs > a.timeMs })
        assertEquals(46.23, s.last().hsCm!!, 0.01)
        assertEquals(-2.724, s.last().tempC!!, 0.001)
    }

    @Test
    fun newSnowFromRealSeries() {
        val s = SnowApi.parse(fixture("imis-gan2.json"))
        // Schneehöhe stieg von ca. 40 auf ca. 46 cm in 23 Stunden
        val n = SnowMath.newSnow24h(s)
        assertNotNull(n)
        assertTrue("Neuschnee $n", n!! in 4.0..8.0)
        val report = SnowApi.report(SnowStationRef("GAN2", "Gandegg", 2710, 1.8), s)
        assertEquals(46.23, report.latest!!.hsCm!!, 0.01)
        assertEquals(n, report.newSnow24hCm)
    }

    private fun series(vararg hs: Double, stepMin: Int = 30) =
        hs.mapIndexed { i, v -> ImisSample(i * stepMin * 60_000L, v, null, null) }

    @Test
    fun noNewSnowWhenDepthDropsOrIsStable() {
        val flat = series(*DoubleArray(48) { 50.0 })
        assertEquals(0.0, SnowMath.newSnow24h(flat)!!, 0.0)
        val settling = series(*DoubleArray(48) { 60.0 - it * 0.2 })
        assertEquals(0.0, SnowMath.newSnow24h(settling)!!, 0.0)
    }

    @Test
    fun spikeIsSmoothedButJumpOverThirtyCmIsRejected() {
        val spike = DoubleArray(48) { 50.0 }.also { it[20] = 58.0 }   // einzelner Ausreißer (8 cm)
        assertEquals(0.0, SnowMath.newSnow24h(series(*spike))!!, 0.0)
        val jump = DoubleArray(48) { if (it < 24) 50.0 else 95.0 }    // 45 cm Sprung: Messfehler
        assertNull(SnowMath.newSnow24h(series(*jump)))
    }

    @Test
    fun tooShortOrTooFewValuesGiveNoNewSnow() {
        assertNull(SnowMath.newSnow24h(series(50.0, 51.0, 52.0)))
        assertNull(SnowMath.newSnow24h(series(*DoubleArray(12) { 50.0 + it })))   // nur 5,5 h
    }

    // ---- Lawinenbulletin (Struktur nach der offiziellen Beschreibung, Beispiel selbst gebaut)

    private val lauchernalp = ResortProfiles.byId("lauchernalp")
    private val adelboden = ResortProfiles.byId("adelboden-lenk")
    private val now = java.time.Instant.parse("2026-12-20T08:00:00Z").toEpochMilli()

    private fun bulletinJson(region: String, regionId: String = "CH-4211", endTime: String = "2026-12-21T16:00:00Z") = """
        {"bulletins":[{
          "bulletinID":"b1","lang":"de","publicationTime":"2026-12-19T16:00:00Z",
          "validTime":{"startTime":"2026-12-19T16:00:00Z","endTime":"$endTime"},
          "regions":[{"regionID":"$regionId","name":"$region"}],
          "dangerRatings":[
            {"mainValue":"moderate","elevation":{"upperBound":"2200"},"validTimePeriod":"all_day"},
            {"mainValue":"considerable","elevation":{"lowerBound":"2200"},"aspects":["N","NE"],"validTimePeriod":"all_day"}],
          "avalancheProblems":[{"problemType":"wind_slab","comment":"Frischer <b>Triebschnee</b>.<br/>Vorsicht.","elevation":{"lowerBound":"2200"},"aspects":["N"]}],
          "highlights":"Erhebliche Gefahr oberhalb 2200 m",
          "avalancheActivity":{"highlights":"Aktivität","comment":"Triebschneeansammlungen sind leicht auslösbar."},
          "tendency":[{"comment":"Gefahr nimmt ab."}]
        }]}
    """.trimIndent()

    @Test
    fun emptyBulletinListMeansNoBulletinNeverLowDanger() {
        val list = AvalancheApi.parse("""{"bulletins":[]}""")
        assertTrue(list.isEmpty())
        assertEquals(AvalancheStatus.NoBulletin, AvalancheApi.status(list, lauchernalp, now))
    }

    @Test
    fun parsesBulletinFields() {
        val b = AvalancheApi.parse(bulletinJson("Lötschental")).single()
        assertEquals(3, b.maxLevel)
        assertEquals(listOf("Lötschental"), b.regionNames)
        assertEquals(2, b.dangerLines.size)
        assertEquals("unterhalb 2200 m", b.dangerLines[0].elevationText)
        assertEquals("oberhalb 2200 m", b.dangerLines[1].elevationText)
        assertEquals(listOf("N", "NE"), b.dangerLines[1].aspects)
        assertEquals("Triebschnee", b.problems[0].type)
        assertEquals("Frischer Triebschnee.\nVorsicht.", b.problems[0].comment)
        assertEquals("Gefahr nimmt ab.", b.tendency)
        assertTrue(b.activity!!.contains("leicht auslösbar"))
        assertNotNull(b.validUntilMs)
    }

    @Test
    fun matchesByRegionNameKeyword() {
        val list = AvalancheApi.parse(bulletinJson("Lötschental"))
        val s = AvalancheApi.status(list, lauchernalp, now)
        assertTrue(s is AvalancheStatus.Matched)
        // Anderes Gebiet: es gibt ein Bulletin, aber keins passt
        val other = AvalancheApi.status(list, adelboden, now)
        assertEquals(AvalancheStatus.NotMatched(1), other)
    }

    @Test
    fun matchesByRegionId() {
        val profile = lauchernalp.copy(avalancheRegionIds = listOf("CH-4211"), avalancheRegionKeywords = emptyList())
        val list = AvalancheApi.parse(bulletinJson("Irgendein Name", regionId = "CH-4211"))
        assertTrue(AvalancheApi.status(list, profile, now) is AvalancheStatus.Matched)
    }

    @Test
    fun expiredBulletinIsNotShown() {
        val list = AvalancheApi.parse(bulletinJson("Lötschental", endTime = "2026-12-19T20:00:00Z"))
        assertEquals(AvalancheStatus.NoBulletin, AvalancheApi.status(list, lauchernalp, now))
    }

    @Test
    fun dangerNamesAndLevels() {
        assertEquals(1, AvalancheApi.dangerLevel("low"))
        assertEquals(5, AvalancheApi.dangerLevel("very_high"))
        assertNull(AvalancheApi.dangerLevel("unbekannt"))
        assertEquals("erheblich", AvalancheApi.dangerName(3))
        assertEquals("alle Höhenlagen", AvalancheApi.elevationText(null, null))
        assertEquals("2000 bis 2200 m", AvalancheApi.elevationText("2000", "2200"))
        assertEquals("oberhalb der Waldgrenze", AvalancheApi.elevationText("treeline", null))
    }

    @Test
    fun profilesAreWellFormed() {
        assertEquals(2, ResortProfiles.ALL.size)
        for (p in ResortProfiles.ALL) {
            assertTrue(p.snowStations.isNotEmpty() && p.webcams.isNotEmpty())
            assertTrue(p.webcams.all { it.url.startsWith("https://") })
            assertTrue(p.mountain.elevationM > p.valley.elevationM)
        }
        assertEquals("lauchernalp", ResortProfiles.byId("gibt-es-nicht").id)
    }
}
