package app.pwdr.voice

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import app.pwdr.social.Social
import app.pwdr.tracking.Activity
import app.pwdr.tracking.LiveState

/** Werte des Tages aus früheren Aufzeichnungen, für Rekord und Tageszusammenfassung. */
data class DayBase(val runs: Int = 0, val vertDownM: Double = 0.0, val maxSpeedMs: Double = 0.0)

/**
 * Verbindet Tracking, Planer und Sprachausgabe und nimmt die Kopfhörer-Taste entgegen (PLAN.md 5b):
 * einmal drücken = aktuelle Geschwindigkeit, zweimal = Tageszusammenfassung.
 * Läuft im Tracking-Dienst, alle Aufrufe auf dem Main-Thread.
 */
class VoiceController(context: Context) {
    private val appContext = context.applicationContext
    private val store = VoiceSettingsStore(appContext)
    private val output = VoiceOutput(appContext) { store.load() }
    private val handler = Handler(Looper.getMainLooper())

    private var planner: AnnouncementPlanner? = null
    private val proximity = ProximityAnnouncer()
    private var base = DayBase()
    private var latest = LiveState()

    private var session: MediaSession? = null
    private var silentTrack: AudioTrack? = null
    private var singlePressPending = false
    private val singlePressAction = Runnable {
        singlePressPending = false
        speakSpeedNow()
    }

    /** Beginn der Aufzeichnung, sobald die Werte früherer Aufzeichnungen des Tages bekannt sind. */
    fun begin(dayBase: DayBase) {
        base = dayBase
        planner = AnnouncementPlanner(dayBase.maxSpeedMs)
        if (store.load().buttonEnabled) {
            startMediaSession()
            startSilentStream()
        }
    }

    fun onLive(state: LiveState, timeMs: Long) {
        latest = state
        val p = planner ?: return
        for (a in p.onState(state, timeMs, store.load())) output.offer(a)
    }

    /**
     * Prüft bei jedem eigenen GPS-Fix, ob sich ein Freund nähert. [alt] ist die rohe GPS-Höhe,
     * wie sie Freunde senden. Nutzt die zuletzt empfangenen Positionen der Freunde.
     */
    fun onPosition(lat: Double, lon: Double, alt: Double?, activity: Activity, nowMs: Long = System.currentTimeMillis()) {
        val s = store.load()
        if (!s.friendsEnabled) return
        val nearby = ProximityAnnouncer.nearbyFrom(Social.people.value.filter { it.announce }.map { it.friend }, Social.live.value)
        for (e in proximity.onUpdate(OwnPosition(lat, lon, alt, activity), nearby, s.mutedFriendIds, nowMs)) {
            val text = if (e.names.size == 1 && e.distanceM != null) {
                SpeechText.friendNear(e.names[0], e.distanceM, e.safety)
            } else {
                SpeechText.friendsNear(e.names, e.safety)
            }
            output.offer(
                Announcement(
                    if (e.safety) Kind.SAFETY else Kind.FRIEND,
                    if (e.safety) Priority.HIGH else Priority.MEDIUM,
                    FRIEND_MAX_AGE_MS,
                ) { text },
            )
        }
    }

    fun speakSpeedNow() {
        output.offer(
            Announcement(Kind.ON_DEMAND, Priority.HIGH, 5_000) {
                SpeechText.onDemand(SpeechText.kmh(latest.speedMs), SpeechText.kmh(maxOf(latest.maxSpeedMs, base.maxSpeedMs)))
            },
        )
    }

    fun speakDaySummary() {
        output.offer(
            Announcement(Kind.DAY_SUMMARY, Priority.HIGH, 5_000) {
                SpeechText.daySummary(
                    base.runs + latest.runCount,
                    base.vertDownM + latest.totalVertDownM,
                    SpeechText.kmh(maxOf(latest.maxSpeedMs, base.maxSpeedMs)),
                )
            },
        )
    }

    fun release() {
        handler.removeCallbacks(singlePressAction)
        silentTrack?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        silentTrack = null
        session?.run {
            isActive = false
            release()
        }
        session = null
        planner = null
        output.shutdown()
    }

    /**
     * Android schickt die Kopfhörer-Taste nur an Apps, die gerade Ton ausgeben. Deshalb läuft während der
     * Aufzeichnung ein stiller Strom in Dauerschleife (keine Audio-Focus-Anforderung, Musik läuft weiter).
     */
    private fun startSilentStream() {
        if (silentTrack != null) return
        try {
            val frames = SILENT_RATE_HZ
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SILENT_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
            track.write(ShortArray(frames), 0, frames)
            track.setLoopPoints(0, frames, -1)
            track.play()
            silentTrack = track
        } catch (e: Exception) {
            android.util.Log.w("PWDR-Voice", "Stiller Strom nicht möglich: $e")
        }
    }

    private fun startMediaSession() {
        if (session != null) return
        val s = MediaSession(appContext, "PWDR")
        // Fallback, falls Android die Sitzung nicht als Ziel der Medientasten wählt (z. B. kein Ton aktiv).
        if (Build.VERSION.SDK_INT >= 33) {
            s.setMediaButtonBroadcastReceiver(ComponentName(appContext, MediaButtonReceiver::class.java))
        } else {
            @Suppress("DEPRECATION")
            s.setMediaButtonReceiver(
                PendingIntent.getBroadcast(
                    appContext,
                    0,
                    Intent(Intent.ACTION_MEDIA_BUTTON).setClass(appContext, MediaButtonReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (event != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                    event.keyCode in BUTTON_KEYS
                ) {
                    onButtonPress()
                }
                return true
            }
        })
        // Zustand "spielt", damit Android die Kopfhörer-Taste an diese Sitzung schickt.
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build(),
        )
        s.isActive = true
        session = s
    }

    /** Ein Druck wartet kurz auf einen zweiten, erst dann wird entschieden. */
    fun onButtonPress() {
        if (singlePressPending) {
            handler.removeCallbacks(singlePressAction)
            singlePressPending = false
            speakDaySummary()
        } else {
            singlePressPending = true
            handler.postDelayed(singlePressAction, DOUBLE_PRESS_MS)
        }
    }

    companion object {
        private const val DOUBLE_PRESS_MS = 400L
        private const val FRIEND_MAX_AGE_MS = 10_000L
        private const val SILENT_RATE_HZ = 8_000
        val BUTTON_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
