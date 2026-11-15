package app.pwdr.resort

/** Messpunkt für das Wetter: Koordinate und Höhe, für die Open-Meteo die Prognose berechnet. */
data class WeatherPoint(val label: String, val lat: Double, val lon: Double, val elevationM: Int)

/** Nächste IMIS-Messstation des SLF (PLAN.md 5d). Höhe und Abstand stammen aus der Stationsliste vom 15.11.2026. */
data class SnowStationRef(val code: String, val label: String, val elevationM: Int, val distanceKm: Double)

data class Webcam(val name: String, val source: String, val url: String, val lastChecked: String)

/**
 * Feste Konfiguration pro Skigebiet (PLAN.md 5d, "Skigebiets-Profil"). [id] entspricht dem Gebiet aus den OSM-Daten.
 *
 * Die Lawinenregion ist noch nicht per Kennung hinterlegt: Solange der SLF kein Bulletin ausgibt, lässt sich die
 * Kennung nicht prüfen. [avalancheRegionIds] bleibt deshalb leer und die Zuordnung läuft über [avalancheRegionKeywords]
 * (Teile des Regionsnamens). Beides ist mit dem ersten Bulletin im Winter zu prüfen.
 */
data class ResortProfile(
    val id: String,
    val name: String,
    val mountain: WeatherPoint,
    val valley: WeatherPoint,
    val snowStations: List<SnowStationRef>,
    val avalancheRegionIds: List<String>,
    val avalancheRegionKeywords: List<String>,
    val websiteUrl: String,
    val webcams: List<Webcam>,
)

object ResortProfiles {
    const val BULLETIN_URL = "https://www.slf.ch/de/lawinenbulletin-und-schneesituation/"

    val ALL = listOf(
        ResortProfile(
            id = "lauchernalp",
            name = "Lauchernalp",
            mountain = WeatherPoint("Berg", 46.4256, 7.7582, 2700),
            valley = WeatherPoint("Tal", 46.4056, 7.7850, 1400),
            snowStations = listOf(
                SnowStationRef("GAN2", "Gandegg", 2710, 1.8),
                SnowStationRef("FIS2", "Fisi", 2155, 9.7),
            ),
            avalancheRegionIds = emptyList(),
            avalancheRegionKeywords = listOf("Lötschental", "Lötschen"),
            websiteUrl = "https://www.loetschental.ch/",
            webcams = listOf(
                Webcam(
                    "Bergstation Luftseilbahn Wiler–Lauchernalp", "valais.ch",
                    "https://www.valais.ch/en/destinations/loetschental-region/loetschental/webcams/bergstation-luftseilbahn-wiler-lauchernalp",
                    "15.11.2026",
                ),
                Webcam(
                    "Webcams Lötschental (u. a. Gandegg, Hockenhorngrat)", "valais.ch",
                    "https://www.valais.ch/en/destinations/loetschental-region/loetschental/webcams",
                    "15.11.2026",
                ),
                Webcam("Webcams Lötschental", "loetschental.ch", "https://www.loetschental.ch/en/general/webcams", "15.11.2026"),
            ),
        ),
        ResortProfile(
            id = "adelboden-lenk",
            name = "Adelboden-Lenk",
            mountain = WeatherPoint("Berg", 46.4917, 7.5589, 2100),
            valley = WeatherPoint("Tal", 46.4917, 7.5589, 1350),
            snowStations = listOf(
                SnowStationRef("OTT2", "Ottere", 2017, 5.3),
                SnowStationRef("FAE2", "Färmel", 1971, 5.8),
                SnowStationRef("ADE2", "Engstligenalp", 2304, 6.6),
                SnowStationRef("ADE3", "Lavey-Sattligrat", 2355, 5.6),
            ),
            avalancheRegionIds = emptyList(),
            avalancheRegionKeywords = listOf("Adelboden", "Lenk"),
            websiteUrl = "https://www.adelboden-lenk.ch/",
            webcams = listOf(
                Webcam("Alle Webcams Adelboden-Lenk", "adelboden-lenk.ch", "https://www.adelboden-lenk.ch/de/Service/Webcams", "15.11.2026"),
            ),
        ),
    )

    fun byId(id: String?): ResortProfile = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
