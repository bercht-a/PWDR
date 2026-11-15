package app.pwdr.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Spricht Ansagen aus der [SpeechQueue] über Android-TextToSpeech (PLAN.md 5b, Audio-System).
 * - Audio wie bei Navigations-Apps (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE), Musik wird nur leiser.
 * - Bei Verlust des Audio-Focus (Telefonanruf) wird abgebrochen und die Warteschlange geleert.
 * - Mit "nur mit Kopfhörer" wird ohne angeschlossenen Kopfhörer nichts gesprochen.
 * Alle Aufrufe auf dem Main-Thread.
 */
class VoiceOutput(
    context: Context,
    private val settings: () -> VoiceSettings,
) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val queue = SpeechQueue()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var current: Announcement? = null
    private var focusRequest: AudioFocusRequest? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // Telefonanruf oder andere Sprachausgabe: abbrechen und nichts nachholen.
            queue.clear()
            tts?.stop()
        }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            handler.post {
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TextToSpeech nicht verfügbar: $status")
                    _germanVoiceAvailable.value = false
                    return@post
                }
                val engine = tts ?: return@post
                val result = engine.setLanguage(Locale.GERMAN)
                _germanVoiceAvailable.value =
                    result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                engine.setAudioAttributes(attrs)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = finished()
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finished()
                })
                ready = true
                pump()
            }
        }
    }

    /** Reiht eine Ansage ein. [force] umgeht Hauptschalter und Kopfhörer-Prüfung (Test-Taste). */
    fun offer(a: Announcement, force: Boolean = false) {
        if (!force && !settings().enabled) return
        queue.offer(a, now())
        pump()
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        abandonFocus()
    }

    private fun pump() {
        if (!ready) return
        val running = current
        if (running != null) {
            if (queue.shouldInterrupt(running.priority, now())) tts?.stop()
            return
        }
        val next = queue.next(now())
        if (next == null) {
            // Wartet noch etwas hinter dem Mindestabstand, später erneut versuchen.
            if (queue.size > 0) handler.postDelayed({ pump() }, RETRY_MS)
            return
        }
        val s = settings()
        val force = next.kind == Kind.TEST
        if (!force && s.headphonesOnly && !headphonesConnected()) {
            // Ohne Kopfhörer wird nichts über den Lautsprecher gesprochen. Die Ansage fällt weg.
            Log.d(TAG, "verworfen (kein Kopfhörer): ${next.kind}")
            pump()
            return
        }
        if (!requestFocus()) {
            pump()
            return
        }
        current = next
        val text = next.text()
        Log.d(TAG, "spricht (${next.kind}): $text")
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pwdr-${next.offeredAtMs}")
        if (result != TextToSpeech.SUCCESS) finished()
    }

    private fun finished() {
        handler.post {
            current = null
            abandonFocus()
            queue.onSpoken(now())
            pump()
        }
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        focusRequest = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Kabel, USB oder Bluetooth (A2DP, LE-Audio, Hörgerät) als Ausgabe angeschlossen. */
    fun headphonesConnected(): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "PWDR-Voice"
        private const val RETRY_MS = 500L

        private val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )

        private val _germanVoiceAvailable = MutableStateFlow<Boolean?>(null)

        /** null = noch nicht geprüft, false = keine deutsche Stimme installiert. */
        val germanVoiceAvailable: StateFlow<Boolean?> = _germanVoiceAvailable.asStateFlow()
    }
}
