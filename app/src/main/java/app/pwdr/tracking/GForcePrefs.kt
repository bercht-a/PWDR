package app.pwdr.tracking

import android.content.Context

/** Einstellungen für G-Force: ein- oder ausschalten und Trageposition. Gelten ab der nächsten Aufzeichnung. */
class GForcePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pwdr_prefs", Context.MODE_PRIVATE)

    /** Beschleunigungssensor aufzeichnen (Standard: an). Aus spart Akku und lässt sich zum Vergleich nutzen. */
    var enabled: Boolean
        get() = try {
            prefs.getBoolean("g_enabled", true)
        } catch (e: Exception) {
            true
        }
        set(value) {
            try {
                prefs.edit().putBoolean("g_enabled", value).apply()
            } catch (_: Exception) {
            }
        }

    var position: CarryPosition
        get() = try {
            CarryPosition.of(prefs.getString("g_position", null))
        } catch (e: Exception) {
            CarryPosition.POCKET
        }
        set(value) {
            try {
                prefs.edit().putString("g_position", value.key).apply()
            } catch (_: Exception) {
            }
        }
}
