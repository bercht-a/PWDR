package app.pwdr.update

import android.content.Context
import android.util.Log
import app.pwdr.resort.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Prüft einmal am Tag, ob auf GitHub eine neuere Version von PWDR liegt (öffentliches Repository, keine Anmeldung, keine
 * persönlichen Angaben). Installiert wird nie still: Die App öffnet nur die Download-Adresse.
 */
object UpdateChecker {
    private const val TAG = "PWDR-Update"
    private const val URL = "https://api.github.com/repos/bercht-a/PWDR/releases?per_page=10"

    private val _available = MutableStateFlow<UpdateInfo?>(null)

    /** Neuere Version, die noch nicht weggeklickt wurde. */
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)

    /** Ergebnis der letzten Prüfung von Hand ("Du hast die neueste Version" oder Fehlertext). */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var latest: UpdateInfo? = null
    private var prefs: UpdatePrefs? = null
    private var installed: String? = null

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = UpdatePrefs(app)
        installed = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        _enabled.value = prefs?.enabled ?: true
        publish()
    }

    /** Beim Start: nur wenn eingeschaltet und die letzte Prüfung über einen Tag her ist. */
    suspend fun checkIfDue() {
        val p = prefs ?: return
        if (!p.enabled) return
        if (!UpdateLogic.isDue(p.lastCheckMs, System.currentTimeMillis())) return
        check(manual = false)
    }

    /** Von Hand ("Jetzt prüfen"): immer, auch wenn die automatische Prüfung aus ist. */
    suspend fun checkNow() = check(manual = true)

    private suspend fun check(manual: Boolean) {
        val p = prefs ?: return
        if (manual) _status.value = "Prüfe …"
        try {
            val body = withContext(Dispatchers.IO) { Http.get(URL) }
            val releases = UpdateLogic.parse(body) ?: throw java.io.IOException("Antwort nicht lesbar")
            p.lastCheckMs = System.currentTimeMillis()
            latest = UpdateLogic.newer(installed, releases)
            publish()
            if (manual) {
                val l = latest
                _status.value = if (l != null) "Neue Version ${l.version} verfügbar." else "Du hast die neueste Version."
            }
        } catch (e: Exception) {
            // Kein Netz oder GitHub nicht erreichbar: still bleiben, der nächste Start versucht es wieder.
            Log.w(TAG, "Prüfung fehlgeschlagen: ${e.message}")
            if (manual) _status.value = "Prüfung nicht möglich (kein Netz?)."
        }
    }

    fun setEnabled(value: Boolean) {
        prefs?.enabled = value
        _enabled.value = value
        publish()
    }

    /** "Später": Diese Version wird nicht mehr angezeigt, erst die nächste. */
    fun dismiss() {
        val l = latest ?: return
        prefs?.dismissedTag = l.tag
        publish()
    }

    private fun publish() {
        val l = latest
        _available.value = if (l != null && (prefs?.enabled != false) && prefs?.dismissedTag != l.tag) l else null
    }
}
