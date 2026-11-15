package app.pwdr.social

import android.location.Location
import android.util.Log
import app.pwdr.tracking.Activity
import app.pwdr.tracking.haversineM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sendet im Tracking-Dienst die eigene Position, solange eine Freigabe läuft (PLAN.md 5c). Es wird nur der
 * aktuelle Stand gesendet, nie nachgeschickt. Ohne Netz bleibt das Senden still, bis es wieder klappt.
 */
class ShareSender(private val scope: CoroutineScope) {
    private val scheduler = ShareScheduler()
    private var inFlight = false
    private var lastSessionId: String? = null

    fun onLocation(loc: Location, activity: Activity, nowMs: Long = System.currentTimeMillis()) {
        val share = Social.share.value
        if (!share.active || share.expiresAtMs <= nowMs) return
        if (share.sessionId != lastSessionId) {
            lastSessionId = share.sessionId
            scheduler.reset()
        }
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) {
            Social.reportSkip("GPS zu ungenau: ${loc.accuracy.toInt()} m")
            return
        }
        Social.reportSkip(null)

        val nearest = nearestFriendM(loc, nowMs)
        if (!scheduler.onFix(loc.latitude, loc.longitude, activity, nearest, nowMs)) return
        if (inFlight) return
        inFlight = true
        scope.launch {
            try {
                Social.updateLocation(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    alt = if (loc.hasAltitude()) loc.altitude else null,
                    speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                    heading = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                    accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                    state = activity.toShareState(),
                )
                Social.reportSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = e.message.orEmpty()
                Social.reportSendError(text.ifBlank { e.toString() })
                if ("no_active_share" in text) Social.markShareEnded()
                else Log.d(TAG, "Senden fehlgeschlagen: ${text.take(120)}")
            } finally {
                inFlight = false
            }
        }
    }

    /** Entfernung zum nächsten Freund mit aktueller Position, null wenn keiner bekannt. */
    private fun nearestFriendM(loc: Location, nowMs: Long): Double? =
        Social.live.value.values
            .mapNotNull { row ->
                val lat = row.lat ?: return@mapNotNull null
                val lon = row.lon ?: return@mapNotNull null
                val updated = try {
                    parseInstantMs(row.updatedAt)
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                if (FriendFreshness.of(updated, nowMs) != Freshness.FRESH) null
                else haversineM(loc.latitude, loc.longitude, lat, lon)
            }
            .minOrNull()

    private companion object {
        const val TAG = "PWDR-Share"
        /** Ungenaue Positionen werden nicht geteilt, sie würden Freunde in die Irre führen. */
        const val MAX_ACCURACY_M = 50f
    }
}
