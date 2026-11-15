package app.pwdr.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import app.pwdr.tracking.TrackingService

/**
 * Nimmt die Kopfhörer-Taste entgegen, wenn Android sie über den Medientasten-Empfänger der Sitzung schickt,
 * und gibt sie an den laufenden Tracking-Dienst weiter. Ohne laufende Aufzeichnung passiert nichts.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        if (event.keyCode !in VoiceController.BUTTON_KEYS) return
        TrackingService.sendButtonPress(context)
    }
}
