package app.pwdr.social

import app.pwdr.tracking.Activity
import app.pwdr.tracking.haversineM

/**
 * Entscheidet bei jedem GPS-Fix, ob die eigene Position jetzt gesendet wird (PLAN.md 5c, adaptiver Takt).
 * Merkt sich dafür den letzten Sendezeitpunkt und seit wann man sich kaum bewegt hat.
 * Ein fehlgeschlagenes Senden zählt trotzdem als gesendet, damit ohne Netz nicht bei jedem Fix neu versucht wird.
 * Unabhängig von Android.
 */
class ShareScheduler {
    private var lastSentMs: Long? = null
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorSinceMs: Long? = null

    /** True, wenn jetzt gesendet werden soll. */
    fun onFix(lat: Double, lon: Double, activity: Activity, nearestFriendM: Double?, nowMs: Long): Boolean {
        val since = anchorSinceMs
        if (since == null || haversineM(anchorLat, anchorLon, lat, lon) > STILL_RADIUS_M) {
            anchorLat = lat
            anchorLon = lon
            anchorSinceMs = nowMs
        }
        val stillFor = nowMs - (anchorSinceMs ?: nowMs)
        val interval = ShareTiming.intervalMs(activity, nearestFriendM, stillFor)
        if (!ShareTiming.due(lastSentMs, nowMs, interval)) return false
        lastSentMs = nowMs
        return true
    }

    /** Neue Freigabe: sofort wieder senden, ohne den alten Takt. */
    fun reset() {
        lastSentMs = null
        anchorSinceMs = null
    }

    companion object {
        /** Bewegung unter dieser Strecke zählt als Stillstand (GPS-Rauschen). */
        const val STILL_RADIUS_M = 15.0
    }
}

/** Zustand des Tracking-Dienstes als Text für die Datenbank (downhill, lift, pause). */
fun Activity.toShareState(): String = when (this) {
    Activity.DOWNHILL -> "downhill"
    Activity.LIFT -> "lift"
    Activity.PAUSE -> "pause"
}
