package app.pwdr.voice

import app.pwdr.social.Friend
import app.pwdr.social.FriendFreshness
import app.pwdr.social.LiveRow
import app.pwdr.social.parseInstantMs
import app.pwdr.tracking.Activity
import app.pwdr.tracking.haversineM
import kotlin.math.abs

/** Eigene Position für die Annäherungsprüfung. [alt] ist die rohe GPS-Höhe, wie sie auch Freunde senden. */
data class OwnPosition(val lat: Double, val lon: Double, val alt: Double?, val activity: Activity)

/** Letzter bekannter Stand eines Freundes. [state] ist downhill, lift oder pause. */
data class NearbyFriend(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val state: String?,
    val updatedMs: Long,
)

/** Eine fällige Ansage. [distanceM] ist nur bei einem einzelnen Freund gesetzt. */
data class ProximityEvent(val names: List<String>, val safety: Boolean, val distanceM: Double?)

/**
 * Entscheidet, wann ein sich nähernder Freund angesagt wird (PLAN.md 5b, "Annäherung an Freunde").
 *
 * - Stufen 300, 150 und 50 m. Ansage nur beim Überschreiten einer Stufe von außen nach innen.
 * - Hysterese: Eine Stufe wird erst wieder frei, wenn der Freund mehr als das 1,5-Fache der Stufe entfernt ist.
 * - Wartezeit: pro Freund höchstens eine Ansage alle 60 s.
 * - Höhenfilter: Über 30 m Höhenunterschied gilt ein Freund als "nicht auf gleicher Ebene" und löst nichts aus.
 * - Aktualität: Positionen über 30 s alt werden ignoriert.
 * - Beide im Lift: keine Ansage.
 * - Mehrere Freunde gleichzeitig werden zu einer Ansage gebündelt. Die engste Stufe (50 m) gilt als Sicherheitsansage.
 *
 * Unabhängig von Android, die Uhr wird übergeben.
 */
class ProximityAnnouncer(
    private val thresholdsM: List<Double> = DEFAULT_THRESHOLDS_M,
) {
    private class State(var level: Int = 0, var lastAnnouncedMs: Long = Long.MIN_VALUE / 2)

    private val states = HashMap<String, State>()

    fun onUpdate(own: OwnPosition, friends: List<NearbyFriend>, mutedIds: Set<String>, nowMs: Long): List<ProximityEvent> {
        // Freunde, die nicht mehr geteilt werden, vergessen, damit sie später wieder angesagt werden können.
        states.keys.retainAll(friends.map { it.id }.toSet())

        data class Trigger(val friend: NearbyFriend, val distanceM: Double, val level: Int)
        val triggers = ArrayList<Trigger>()

        for (f in friends) {
            if (!FriendFreshness.usableForAnnouncement(f.updatedMs, nowMs)) continue
            if (f.id in mutedIds) continue
            val ownAlt = own.alt
            val friendAlt = f.alt
            if (ownAlt != null && friendAlt != null && abs(ownAlt - friendAlt) > MAX_HEIGHT_DIFF_M) continue
            if (own.activity == Activity.LIFT && f.state == "lift") continue

            val d = haversineM(own.lat, own.lon, f.lat, f.lon)
            val state = states.getOrPut(f.id) { State() }

            state.level = minOf(state.level, releasedLevel(d))

            val level = levelFor(d)
            if (level > state.level && nowMs - state.lastAnnouncedMs >= COOLDOWN_MS) {
                triggers.add(Trigger(f, d, level))
            }
        }
        if (triggers.isEmpty()) return emptyList()

        for (t in triggers) {
            val s = states.getValue(t.friend.id)
            s.level = t.level
            s.lastAnnouncedMs = nowMs
        }
        val safety = triggers.any { it.level >= thresholdsM.size }
        val names = triggers.sortedBy { it.distanceM }.map { it.friend.name }
        return listOf(ProximityEvent(names, safety, if (triggers.size == 1) triggers[0].distanceM else null))
    }

    /** Stufe für die Entfernung: 0 außerhalb, 1 unter der ersten Schwelle, und so weiter. */
    private fun levelFor(d: Double): Int = thresholdsM.count { d < it }

    /** Stufe, die nach dem Entfernen noch als "belegt" gilt (1,5-fache Schwelle). */
    private fun releasedLevel(d: Double): Int = thresholdsM.count { d <= it * HYSTERESIS }

    companion object {
        /**
         * Baut aus Freundesliste und Live-Positionen die Eingabe für [onUpdate]: nur akzeptierte Freunde mit Position,
         * mit Aussprache-Name (falls gesetzt) statt Anzeigename. Einträge ohne gültigen Zeitstempel fallen weg.
         */
        fun nearbyFrom(friends: List<Friend>, live: Map<String, LiveRow>): List<NearbyFriend> {
            val byId = friends.filter { it.status == "accepted" }.associateBy { it.friendId }
            return live.values.mapNotNull { row ->
                val friend = byId[row.userId] ?: return@mapNotNull null
                val lat = row.lat ?: return@mapNotNull null
                val lon = row.lon ?: return@mapNotNull null
                val updated = try {
                    parseInstantMs(row.updatedAt)
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                NearbyFriend(row.userId, friend.spokenName?.ifBlank { null } ?: friend.displayName, lat, lon, row.alt, row.state, updated)
            }
        }

        val DEFAULT_THRESHOLDS_M = listOf(300.0, 150.0, 50.0)
        const val HYSTERESIS = 1.5
        const val COOLDOWN_MS = 60_000L
        const val MAX_HEIGHT_DIFF_M = 30.0
    }
}
