package app.pwdr.update

import android.content.Context

/** Einstellungen der Aktualisierungs-Prüfung. Fehler beim Lesen oder Schreiben sind harmlos. */
class UpdatePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pwdr_update", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = try { prefs.getBoolean("enabled", true) } catch (_: Exception) { true }
        set(value) { try { prefs.edit().putBoolean("enabled", value).apply() } catch (_: Exception) {} }

    var lastCheckMs: Long
        get() = try { prefs.getLong("last_check", 0L) } catch (_: Exception) { 0L }
        set(value) { try { prefs.edit().putLong("last_check", value).apply() } catch (_: Exception) {} }

    var dismissedTag: String?
        get() = try { prefs.getString("dismissed", null) } catch (_: Exception) { null }
        set(value) { try { prefs.edit().putString("dismissed", value).apply() } catch (_: Exception) {} }
}
