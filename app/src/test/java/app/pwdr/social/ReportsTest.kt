package app.pwdr.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsTest {
    private fun row(id: String = "r1", type: String = "queue", expires: String = "2026-11-15T12:00:00+00:00") =
        ReportRow(id, type, 46.4, 7.7, "Lift", "2026-11-15T10:00:00+00:00", expires)

    private val noon = parseInstantMs("2026-11-15T11:00:00+00:00")

    @Test fun expiredReportsAreDropped() {
        val rows = listOf(row("a", expires = "2026-11-15T10:59:00+00:00"), row("b"))
        assertEquals(listOf("b"), ReportsOnMap.active(rows, noon).map { it.id })
    }

    @Test fun unparsableExpiryIsDropped() {
        assertTrue(ReportsOnMap.active(listOf(row(expires = "kaputt")), noon).isEmpty())
    }

    @Test fun geoJsonHasTypeColorAndLonLatOrder() {
        val json = ReportsOnMap.geoJson(listOf(row(type = "warning")))
        assertTrue(json.contains("\"color\":\"#FF5A5F\""))
        assertTrue(json.contains("\"id\":\"r1\""))
        assertTrue(json.contains("[7.700000,46.400000]"))
    }

    @Test fun geoJsonStripsOddCharactersFromId() {
        assertTrue(ReportsOnMap.geoJson(listOf(row(id = "a\"b"))).contains("\"id\":\"ab\""))
    }

    @Test fun unknownTypeFallsBackToOther() {
        assertEquals(ReportType.OTHER, ReportType.of("quatsch"))
        assertEquals(ReportType.OTHER, ReportType.of(null))
    }

    @Test fun ageAndRemainingTexts() {
        assertEquals("gerade eben", ReportsOnMap.ageText(1000, 1000 + 30_000))
        assertEquals("vor 12 min", ReportsOnMap.ageText(0, 12 * 60_000L))
        assertEquals("vor 1 h 5 min", ReportsOnMap.ageText(0, 65 * 60_000L))
        assertEquals("noch 80 min".replace("80 min", "1 h 20 min"), ReportsOnMap.remainingText(80 * 60_000L, 0))
        assertEquals("läuft gleich ab", ReportsOnMap.remainingText(0, 5_000))
    }

    @Test fun errorTexts() {
        assertTrue(ReportsOnMap.errorText("too_many_reports").startsWith("Zu viele Meldungen"))
        assertTrue(ReportsOnMap.errorText("own_report").contains("eigene"))
        assertTrue(ReportsOnMap.errorText("boom\nzweite Zeile").contains("(boom)"))
    }

    @Test fun ttlMatchesServer() {
        assertEquals(2, ReportType.QUEUE.ttlHours)
        assertEquals(6, ReportType.SNOW.ttlHours)
        assertEquals(4, ReportType.PISTE.ttlHours)
        assertEquals(6, ReportType.APRES.ttlHours)
    }

    @Test fun apresGeoJsonCarriesType() {
        val json = ReportsOnMap.geoJson(listOf(row(type = "apres")))
        assertTrue(json.contains("\"type\":\"apres\""))
        assertEquals(ReportType.APRES, ReportType.of("apres"))
    }
}
