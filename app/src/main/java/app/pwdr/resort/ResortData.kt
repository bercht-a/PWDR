package app.pwdr.resort

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/** Zustand eines Abschnitts der Gebietsseite. Jede Quelle fällt einzeln aus (PLAN.md 5d, "Fehlerverhalten"). */
sealed interface Section<out T> {
    data object Idle : Section<Nothing>
    data object Loading : Section<Nothing>

    /** [fromCache] ist true, wenn der Abruf scheiterte und der gespeicherte Stand gezeigt wird. */
    data class Ready<T>(val value: T, val fetchedAtMs: Long, val fromCache: Boolean) : Section<T>

    data class Failed(val message: String) : Section<Nothing>
}

data class WeatherReport(val mountain: WeatherSite, val valley: WeatherSite?)

/** Einfacher HTTP-Abruf mit User-Agent ohne persönliche Angaben. */
object Http {
    fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "PWDR/0.1 (private hobby project)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/** Speichert die letzte Antwort jeder Quelle mit Zeitstempel, damit die Seite auch ohne Netz etwas zeigt. */
class ResortCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pwdr_resort_cache", Context.MODE_PRIVATE)

    fun get(key: String): Pair<String, Long>? = try {
        val body = prefs.getString("b:$key", null)
        val time = prefs.getLong("t:$key", 0)
        if (body != null && time > 0) body to time else null
    } catch (e: Exception) {
        null
    }

    fun put(key: String, body: String, nowMs: Long) {
        try {
            prefs.edit().putString("b:$key", body).putLong("t:$key", nowMs).apply()
        } catch (_: Exception) {
        }
    }
}

/**
 * Holt Wetter, Schnee und Lawinenbulletin für ein Gebiet (PLAN.md 5d). Direkt von der App an die Quellen,
 * ohne eigenen Server. Aktualität: Wetter 60 min, Schnee und Bulletin 30 min. Ohne Netz gilt der gespeicherte Stand.
 */
object ResortData {
    private const val TAG = "PWDR-Resort"
    private const val WEATHER_TTL_MS = 60 * 60_000L
    private const val SNOW_TTL_MS = 30 * 60_000L
    private const val BULLETIN_TTL_MS = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _weather = MutableStateFlow<Section<WeatherReport>>(Section.Idle)
    val weather: StateFlow<Section<WeatherReport>> = _weather.asStateFlow()

    private val _snow = MutableStateFlow<Section<List<SnowStationReport>>>(Section.Idle)
    val snow: StateFlow<Section<List<SnowStationReport>>> = _snow.asStateFlow()

    private val _avalanche = MutableStateFlow<Section<AvalancheStatus>>(Section.Idle)
    val avalanche: StateFlow<Section<AvalancheStatus>> = _avalanche.asStateFlow()

    private class Fetched(val body: String, val timeMs: Long, val fromCache: Boolean)

    /** Lädt alle drei Abschnitte parallel. [force] ignoriert die Aktualität (Aktualisieren-Knopf). */
    fun load(context: Context, resort: ResortProfile, force: Boolean = false) {
        val cache = ResortCache(context)
        _weather.value = Section.Loading
        _snow.value = Section.Loading
        _avalanche.value = Section.Loading
        scope.launch { _weather.value = loadWeather(cache, resort, force) }
        scope.launch { _snow.value = loadSnow(cache, resort, force) }
        scope.launch { _avalanche.value = loadAvalanche(cache, resort, force) }
    }

    private fun fetch(cache: ResortCache, key: String, url: String, ttlMs: Long, force: Boolean): Fetched? {
        val now = System.currentTimeMillis()
        val cached = cache.get(key)
        if (!force && cached != null && now - cached.second < ttlMs) return Fetched(cached.first, cached.second, false)
        return try {
            val body = Http.get(url)
            cache.put(key, body, now)
            Fetched(body, now, false)
        } catch (e: Exception) {
            Log.w(TAG, "Abruf $key fehlgeschlagen: $e")
            cached?.let { Fetched(it.first, it.second, true) }
        }
    }

    private suspend fun loadWeather(cache: ResortCache, resort: ResortProfile, force: Boolean): Section<WeatherReport> =
        coroutineScope {
            val mountain = async { fetch(cache, "weather-${resort.id}-mountain", WeatherApi.url(resort.mountain), WEATHER_TTL_MS, force) }
            val valley = async { fetch(cache, "weather-${resort.id}-valley", WeatherApi.url(resort.valley), WEATHER_TTL_MS, force) }
            val m = mountain.await() ?: return@coroutineScope Section.Failed("Wetterdaten zurzeit nicht verfügbar.")
            try {
                val site = WeatherApi.parse(m.body, resort.mountain.elevationM)
                val v = valley.await()?.let { runCatching { WeatherApi.parse(it.body, resort.valley.elevationM) }.getOrNull() }
                Section.Ready(WeatherReport(site, v), m.timeMs, m.fromCache)
            } catch (e: Exception) {
                Log.w(TAG, "Wetter lesen: $e")
                Section.Failed("Wetterdaten zurzeit nicht verfügbar.")
            }
        }

    private suspend fun loadSnow(cache: ResortCache, resort: ResortProfile, force: Boolean): Section<List<SnowStationReport>> =
        coroutineScope {
            val results = resort.snowStations.map { ref ->
                async {
                    val f = fetch(cache, "snow-${ref.code}", SnowApi.url(ref.code), SNOW_TTL_MS, force) ?: return@async null
                    val samples = runCatching { SnowApi.parse(f.body) }.getOrNull() ?: return@async null
                    if (samples.isEmpty()) null else SnowApi.report(ref, samples) to f
                }
            }.awaitAll().filterNotNull()
            if (results.isEmpty()) {
                Section.Failed("Schneedaten zurzeit nicht verfügbar.")
            } else {
                val oldest = results.minOf { it.second.timeMs }
                Section.Ready(results.map { it.first }, oldest, results.any { it.second.fromCache })
            }
        }

    private fun loadAvalanche(cache: ResortCache, resort: ResortProfile, force: Boolean): Section<AvalancheStatus> {
        val f = fetch(cache, "avalanche-de", AvalancheApi.url("de"), BULLETIN_TTL_MS, force)
            ?: return Section.Failed("Das Lawinenbulletin ist zurzeit nicht abrufbar.")
        return try {
            val bulletins = AvalancheApi.parse(f.body)
            Section.Ready(AvalancheApi.status(bulletins, resort, System.currentTimeMillis()), f.timeMs, f.fromCache)
        } catch (e: Exception) {
            Log.w(TAG, "Bulletin lesen: $e")
            Section.Failed("Das Lawinenbulletin konnte nicht gelesen werden.")
        }
    }
}
