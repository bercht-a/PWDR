package app.pwdr.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Version der Form 0.1.0-alpha.8 (ein führendes "v" ist erlaubt). Ohne Android, damit sich der Vergleich testen lässt. */
data class AppVersion(val core: List<Int>, val pre: List<String>) : Comparable<AppVersion> {
    val isPrerelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: AppVersion): Int {
        val n = maxOf(core.size, other.core.size)
        for (i in 0 until n) {
            val c = (core.getOrNull(i) ?: 0).compareTo(other.core.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        // Eine Vorabversion ist älter als die gleiche Version ohne Zusatz (1.0.0-rc.1 < 1.0.0).
        if (pre.isEmpty() && other.pre.isEmpty()) return 0
        if (pre.isEmpty()) return 1
        if (other.pre.isEmpty()) return -1
        val m = maxOf(pre.size, other.pre.size)
        for (i in 0 until m) {
            val a = pre.getOrNull(i) ?: return -1
            val b = other.pre.getOrNull(i) ?: return 1
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // Zahlen sind kleiner als Text
                bn != null -> 1
                else -> a.compareTo(b) // alpha < beta < rc
            }
            if (c != 0) return c
        }
        return 0
    }

    companion object {
        /** Null, wenn der Text keine Version ist. Zusätze nach "+" (Build-Angaben) zählen nicht. */
        fun parse(text: String?): AppVersion? {
            if (text == null) return null
            val t = text.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            if (t.isEmpty()) return null
            val core = t.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
            if (core.isEmpty() || core.size > 4) return null
            val pre = if ('-' in t) t.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
            return AppVersion(core, pre)
        }
    }
}

@Serializable
data class ReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
)

/** Ein GitHub-Release (Antwort von /repos/.../releases). */
@Serializable
data class ReleaseDto(
    @SerialName("tag_name") val tag: String = "",
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
)

/** Eine verfügbare neuere Version. [apkUrl] ist leer, wenn das Release keine APK-Datei enthält. */
data class UpdateInfo(val version: String, val tag: String, val pageUrl: String, val apkUrl: String) {
    /** Wohin der Knopf führt: direkt zur APK, sonst zur Release-Seite. */
    val target: String get() = apkUrl.ifEmpty { pageUrl }
}

object UpdateLogic {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Nur diese Adressen darf die Aktualisierungs-Karte öffnen. */
    private const val ALLOWED_PREFIX = "https://github.com/"

    fun parse(body: String): List<ReleaseDto>? = try {
        json.decodeFromString<List<ReleaseDto>>(body)
    } catch (_: Exception) {
        null
    }

    /**
     * Neueste Version, die neuer ist als die installierte, oder null. Entwürfe zählen nie. Vorabversionen zählen nur, wenn
     * auch die installierte eine ist (wer eine stabile Version nutzt, bekommt keine Alpha angeboten). Versionen, die sich
     * nicht lesen lassen, und Adressen ausserhalb von github.com werden übersprungen.
     */
    fun newer(installed: String?, releases: List<ReleaseDto>): UpdateInfo? {
        val current = AppVersion.parse(installed) ?: return null
        var best: Pair<AppVersion, ReleaseDto>? = null
        for (r in releases) {
            if (r.draft) continue
            val v = AppVersion.parse(r.tag) ?: continue
            if (r.prerelease && !current.isPrerelease) continue
            if (v <= current) continue
            if (r.htmlUrl.startsWith(ALLOWED_PREFIX).not()) continue
            if (best == null || v > best.first) best = v to r
        }
        val r = best?.second ?: return null
        val apk = r.assets.firstOrNull { it.name.endsWith(".apk") && it.url.startsWith(ALLOWED_PREFIX) }?.url.orEmpty()
        return UpdateInfo(version = r.tag.removePrefix("v"), tag = r.tag, pageUrl = r.htmlUrl, apkUrl = apk)
    }

    /** Ist seit der letzten Prüfung genug Zeit vergangen? Eine Uhr, die zurückgestellt wurde, löst die Prüfung aus. */
    fun isDue(lastCheckMs: Long, nowMs: Long, intervalMs: Long = 24 * 3_600_000L): Boolean =
        lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs
}
