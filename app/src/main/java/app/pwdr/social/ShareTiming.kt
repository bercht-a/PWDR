package app.pwdr.social

import app.pwdr.tracking.Activity

/**
 * Sende-Takt der Live-Position (PLAN.md 5c, "Takt (adaptiv)"). Unabhängig von Android und Netz.
 *
 * | Zustand                                       | Intervall |
 * | Abfahrt                                       | 3 s       |
 * | Lift oder Pause, Freund näher als 500 m       | 5 s       |
 * | Lift oder Pause, kein Freund nah              | 15 s      |
 * | Stillstand länger als 2 min                   | 30 s      |
 */
object ShareTiming {
    const val DOWNHILL_MS = 3_000L
    const val NEAR_FRIEND_MS = 5_000L
    const val FAR_MS = 15_000L
    const val STILL_MS = 30_000L

    const val NEAR_FRIEND_M = 500.0
    const val STILL_AFTER_MS = 2 * 60_000L

    /**
     * @param nearestFriendM Entfernung zum nächsten Freund mit aktueller Position, null wenn unbekannt.
     * @param stillForMs Wie lange die Position schon (nahezu) unverändert ist.
     */
    fun intervalMs(activity: Activity, nearestFriendM: Double?, stillForMs: Long): Long = when {
        activity == Activity.DOWNHILL -> DOWNHILL_MS
        stillForMs > STILL_AFTER_MS -> STILL_MS
        nearestFriendM != null && nearestFriendM < NEAR_FRIEND_M -> NEAR_FRIEND_MS
        else -> FAR_MS
    }

    /** True, wenn seit dem letzten Senden das Intervall verstrichen ist (oder noch nie gesendet wurde). */
    fun due(lastSentMs: Long?, nowMs: Long, intervalMs: Long): Boolean =
        lastSentMs == null || nowMs - lastSentMs >= intervalMs
}

/** Wie aktuell die Position eines Freundes ist (PLAN.md 5c, "Staleness"). */
enum class Freshness { FRESH, STALE, HIDDEN }

object FriendFreshness {
    /** Ab 60 s ohne Update ausgegraut: ein Freund im Stillstand sendet nur alle 30 s. */
    const val STALE_AFTER_MS = 60_000L

    /** Ab 5 min ausgeblendet. */
    const val HIDDEN_AFTER_MS = 5 * 60_000L

    /** Ansagen (Annäherung) ignorieren Positionen, die älter als 30 s sind. */
    const val ANNOUNCE_MAX_AGE_MS = 30_000L

    fun of(updatedAtMs: Long, nowMs: Long): Freshness {
        val age = nowMs - updatedAtMs
        return when {
            age >= HIDDEN_AFTER_MS -> Freshness.HIDDEN
            age >= STALE_AFTER_MS -> Freshness.STALE
            else -> Freshness.FRESH
        }
    }

    fun usableForAnnouncement(updatedAtMs: Long, nowMs: Long): Boolean = nowMs - updatedAtMs <= ANNOUNCE_MAX_AGE_MS

    /** "vor 2 min" für ausgegraute Freunde, "gerade eben" bei aktuellen. */
    fun ageLabel(updatedAtMs: Long, nowMs: Long): String {
        val age = (nowMs - updatedAtMs).coerceAtLeast(0)
        return when {
            age < 15_000 -> "gerade eben"
            age < 60_000 -> "vor ${age / 1000} s"
            else -> "vor ${age / 60_000} min"
        }
    }
}

/** Wandelt einen Zeitstempel der Datenbank ("2026-11-15T10:11:12.123456+00:00" oder mit Z) in Millisekunden. */
fun parseInstantMs(text: String): Long = java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
