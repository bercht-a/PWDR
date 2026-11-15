package app.pwdr.resort

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime

/** Wetter an einer Höhe (Berg oder Tal). Quelle: Open-Meteo mit MeteoSchweiz-Modell (CC BY 4.0). */
data class WeatherNow(
    val tempC: Double?, val windKmh: Double?, val gustKmh: Double?, val cloudPct: Int?,
    val precipMm: Double?, val snowCm: Double?, val code: Int?,
)

data class WeatherHour(
    val time: LocalDateTime, val tempC: Double?, val precipMm: Double?, val snowCm: Double?,
    val cloudPct: Int?, val windKmh: Double?, val freezingLevelM: Double?,
)

data class WeatherDay(
    val date: LocalDate, val tMaxC: Double?, val tMinC: Double?, val snowSumCm: Double?,
    val precipSumMm: Double?, val gustMaxKmh: Double?, val code: Int?,
)

data class WeatherSite(
    val elevationM: Int,
    val now: WeatherNow?,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
)

@Serializable
private data class OmResponse(
    val current: OmCurrent? = null,
    val hourly: OmHourly? = null,
    val daily: OmDaily? = null,
)

@Serializable
private data class OmCurrent(
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("wind_speed_10m") val wind: Double? = null,
    @SerialName("wind_gusts_10m") val gusts: Double? = null,
    @SerialName("cloud_cover") val cloud: Int? = null,
    val precipitation: Double? = null,
    val snowfall: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
)

@Serializable
private data class OmHourly(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
    val snowfall: List<Double?> = emptyList(),
    @SerialName("cloud_cover") val cloud: List<Int?> = emptyList(),
    @SerialName("wind_speed_10m") val wind: List<Double?> = emptyList(),
    @SerialName("freezing_level_height") val freezingLevel: List<Double?> = emptyList(),
)

@Serializable
private data class OmDaily(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m_max") val tMax: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val tMin: List<Double?> = emptyList(),
    @SerialName("snowfall_sum") val snowSum: List<Double?> = emptyList(),
    @SerialName("precipitation_sum") val precipSum: List<Double?> = emptyList(),
    @SerialName("wind_gusts_10m_max") val gustMax: List<Double?> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
)

object WeatherApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Abfrage-Adresse für einen Messpunkt. `meteoswiss_icon_seamless` ist das MeteoSchweiz-Modell mit Rückfall auf ICON. */
    fun url(p: WeatherPoint): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}&elevation=${p.elevationM}" +
            "&current=temperature_2m,wind_speed_10m,wind_gusts_10m,cloud_cover,precipitation,snowfall,weather_code" +
            "&hourly=temperature_2m,precipitation,snowfall,cloud_cover,wind_speed_10m,freezing_level_height" +
            "&daily=temperature_2m_max,temperature_2m_min,snowfall_sum,precipitation_sum,wind_gusts_10m_max,weather_code" +
            "&models=meteoswiss_icon_seamless&timezone=Europe%2FZurich&forecast_days=5"

    fun parse(body: String, elevationM: Int): WeatherSite {
        val r = json.decodeFromString<OmResponse>(body)
        val c = r.current
        val now = c?.let { WeatherNow(it.temperature, it.wind, it.gusts, it.cloud, it.precipitation, it.snowfall, it.weatherCode) }
        val h = r.hourly
        val hours = h?.time?.mapIndexedNotNull { i, t ->
            val time = runCatching { LocalDateTime.parse(t) }.getOrNull() ?: return@mapIndexedNotNull null
            WeatherHour(
                time, h.temperature.getOrNull(i), h.precipitation.getOrNull(i), h.snowfall.getOrNull(i),
                h.cloud.getOrNull(i), h.wind.getOrNull(i), h.freezingLevel.getOrNull(i),
            )
        } ?: emptyList()
        val d = r.daily
        val days = d?.time?.mapIndexedNotNull { i, t ->
            val date = runCatching { LocalDate.parse(t) }.getOrNull() ?: return@mapIndexedNotNull null
            WeatherDay(
                date, d.tMax.getOrNull(i), d.tMin.getOrNull(i), d.snowSum.getOrNull(i),
                d.precipSum.getOrNull(i), d.gustMax.getOrNull(i), d.weatherCode.getOrNull(i),
            )
        } ?: emptyList()
        return WeatherSite(elevationM, now, hours, days)
    }

    /** WMO-Wettercode als kurzer deutscher Text. */
    fun describe(code: Int?): String = when (code) {
        null -> "–"
        0 -> "Klar"
        1 -> "Überwiegend klar"
        2 -> "Teils bewölkt"
        3 -> "Bedeckt"
        45, 48 -> "Nebel"
        51, 53, 55 -> "Nieselregen"
        56, 57 -> "Gefrierender Nieselregen"
        61 -> "Leichter Regen"
        63 -> "Regen"
        65 -> "Starker Regen"
        66, 67 -> "Gefrierender Regen"
        71 -> "Leichter Schneefall"
        73 -> "Schneefall"
        75 -> "Starker Schneefall"
        77 -> "Schneegriesel"
        80, 81, 82 -> "Regenschauer"
        85 -> "Schneeschauer"
        86 -> "Starke Schneeschauer"
        95 -> "Gewitter"
        96, 99 -> "Gewitter mit Hagel"
        else -> "Wettercode $code"
    }

    /** Stunden ab [from] (inklusive der laufenden Stunde), alle [stepHours] Stunden, höchstens [count] Stück. */
    fun upcoming(hours: List<WeatherHour>, from: LocalDateTime, stepHours: Int = 3, count: Int = 8): List<WeatherHour> =
        hours.filter { !it.time.isBefore(from.withMinute(0).withSecond(0).withNano(0)) }
            .filterIndexed { i, _ -> i % stepHours == 0 }
            .take(count)
}
