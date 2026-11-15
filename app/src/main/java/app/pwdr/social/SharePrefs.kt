package app.pwdr.social

import android.content.Context

/**
 * Einstellungen zum automatischen Teilen. Beim Aufzeichnen sehen alle Freunde die eigene Position, ausser sie sind
 * ausgenommen oder das automatische Teilen ist aus. Die Freigabe endet mit dem Stopp der Aufzeichnung.
 */
class SharePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pwdr_prefs", Context.MODE_PRIVATE)

    var autoShare: Boolean
        get() = try {
            prefs.getBoolean("share_auto", true)
        } catch (e: Exception) {
            true
        }
        set(value) {
            try {
                prefs.edit().putBoolean("share_auto", value).apply()
            } catch (_: Exception) {
            }
        }

    /** Freunde, mit denen nie geteilt wird. */
    var excluded: Set<String>
        get() = try {
            prefs.getStringSet("share_excluded", emptySet())?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        set(value) {
            try {
                prefs.edit().putStringSet("share_excluded", value).apply()
            } catch (_: Exception) {
            }
        }
}

/** Auswahl der Freunde für das automatische Teilen. Unabhängig von Android. */
object ShareTargets {
    /** Alle akzeptierten Freunde ausser den ausgenommenen. Blockierte erscheinen nie. */
    fun select(friends: List<Friend>, excluded: Set<String>): List<String> =
        friends.filter { it.status == "accepted" && it.friendId !in excluded }.map { it.friendId }

    /** Freunde, die noch nicht freigegeben sind (z. B. weil sie während des Teilens neu dazukamen). */
    fun missing(selected: List<String>, current: Set<String>): List<String> = selected.filter { it !in current }
}
