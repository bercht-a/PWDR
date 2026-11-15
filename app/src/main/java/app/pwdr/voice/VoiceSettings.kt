package app.pwdr.voice

import android.content.Context

/**
 * Einstellungen der Sprachausgabe (PLAN.md 5b). Standard: Sprache an, nur mit Kopfhörer,
 * Speed-Intervall aus, Höchstwert und Abfahrt-Ende an.
 */
data class VoiceSettings(
    val enabled: Boolean = true,
    val headphonesOnly: Boolean = true,
    /** 0 = aus. */
    val speedIntervalSec: Int = 0,
    /** Intervall-Ansage nur, wenn sich die Geschwindigkeit seit der letzten Ansage um so viel änderte. */
    val speedMinChangeKmh: Int = 5,
    val recordEnabled: Boolean = true,
    val runEndEnabled: Boolean = true,
    /** Kopfhörer-Taste auswerten. Hält dafür einen stillen Audio-Strom offen (Akku, Bluetooth-Kanal). */
    val buttonEnabled: Boolean = true,
    /** Ansage, wenn sich ein Freund nähert. */
    val friendsEnabled: Boolean = true,
    /** Freunde, die nie angesagt werden. */
    val mutedFriendIds: Set<String> = emptySet(),
) {
    companion object {
        val INTERVALS_SEC = listOf(0, 10, 20, 30, 60)
    }
}

/** Speichert die Einstellungen in SharedPreferences. Fehler beim Lesen führen zu den Standardwerten. */
class VoiceSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pwdr_prefs", Context.MODE_PRIVATE)

    fun load(): VoiceSettings {
        val d = VoiceSettings()
        return try {
            VoiceSettings(
                enabled = prefs.getBoolean("voice_enabled", d.enabled),
                headphonesOnly = prefs.getBoolean("voice_headphones_only", d.headphonesOnly),
                speedIntervalSec = prefs.getInt("voice_speed_interval", d.speedIntervalSec),
                speedMinChangeKmh = prefs.getInt("voice_speed_min_change", d.speedMinChangeKmh),
                recordEnabled = prefs.getBoolean("voice_record", d.recordEnabled),
                runEndEnabled = prefs.getBoolean("voice_run_end", d.runEndEnabled),
                buttonEnabled = prefs.getBoolean("voice_button", d.buttonEnabled),
                friendsEnabled = prefs.getBoolean("voice_friends", d.friendsEnabled),
                mutedFriendIds = prefs.getStringSet("voice_muted_friends", emptySet())?.toSet() ?: emptySet(),
            )
        } catch (e: Exception) {
            d
        }
    }

    fun save(s: VoiceSettings) {
        try {
            prefs.edit()
                .putBoolean("voice_enabled", s.enabled)
                .putBoolean("voice_headphones_only", s.headphonesOnly)
                .putInt("voice_speed_interval", s.speedIntervalSec)
                .putInt("voice_speed_min_change", s.speedMinChangeKmh)
                .putBoolean("voice_record", s.recordEnabled)
                .putBoolean("voice_run_end", s.runEndEnabled)
                .putBoolean("voice_button", s.buttonEnabled)
                .putBoolean("voice_friends", s.friendsEnabled)
                .putStringSet("voice_muted_friends", s.mutedFriendIds)
                .apply()
        } catch (_: Exception) {
        }
    }
}
