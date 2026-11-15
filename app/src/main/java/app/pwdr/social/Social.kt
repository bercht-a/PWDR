package app.pwdr.social

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class AuthState { LOADING, SIGNED_OUT, SIGNED_IN }

/** Eigene Freigabe: wer sie gerade sieht und bis wann sie gilt. */
data class ShareState(
    val active: Boolean = false,
    val sessionId: String? = null,
    val expiresAtMs: Long = 0,
    val friendIds: Set<String> = emptySet(),
)

/**
 * Zustand und Aktionen für Konto, Freunde und Standort-Freigabe (PLAN.md 5c). Eine einzige Stelle für Oberfläche
 * und Tracking-Dienst. Alle Aktionen laufen im Hintergrund und melden Fehler als deutschen Text in [message].
 */
object Social {
    private const val TAG = "PWDR-Social"
    private const val AUTO_SHARE_HOURS = 12
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client get() = Backend.client

    private val _authState = MutableStateFlow(AuthState.LOADING)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    /** False, solange das Profil noch geladen wird (um "kein Profil" von "noch unbekannt" zu trennen). */
    private val _profileLoaded = MutableStateFlow(false)
    val profileLoaded: StateFlow<Boolean> = _profileLoaded.asStateFlow()

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _share = MutableStateFlow(ShareState())
    val share: StateFlow<ShareState> = _share.asStateFlow()

    private val _live = MutableStateFlow<Map<String, LiveRow>>(emptyMap())
    val live: StateFlow<Map<String, LiveRow>> = _live.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _linkSentTo = MutableStateFlow<String?>(null)
    val linkSentTo: StateFlow<String?> = _linkSentTo.asStateFlow()

    private val _inviteCode = MutableStateFlow<String?>(null)
    val inviteCode: StateFlow<String?> = _inviteCode.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Code aus einem Einladungslink (pwdr://join/CODE), der noch eingelöst werden soll. */
    val pendingJoinCode = MutableStateFlow<String?>(null)

    private var started = false
    private var liveJob: Job? = null
    private var sharePrefs: SharePrefs? = null

    private val _autoShare = MutableStateFlow(true)

    /** Beim Aufzeichnen automatisch mit allen Freunden teilen (Standard: an). */
    val autoShare: StateFlow<Boolean> = _autoShare.asStateFlow()

    private val _excluded = MutableStateFlow<Set<String>>(emptySet())

    /** Freunde, mit denen nie geteilt wird. */
    val excluded: StateFlow<Set<String>> = _excluded.asStateFlow()

    /** Einmal beim App-Start aufrufen. */
    fun start(context: Context) {
        if (started) return
        started = true
        val prefs = SharePrefs(context)
        sharePrefs = prefs
        _autoShare.value = prefs.autoShare
        _excluded.value = prefs.excluded
        scope.launch {
            client.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        _authState.value = AuthState.SIGNED_IN
                        refreshAll()
                        startRealtime()
                    }
                    is SessionStatus.Initializing -> _authState.value = AuthState.LOADING
                    is SessionStatus.NotAuthenticated -> {
                        _authState.value = AuthState.SIGNED_OUT
                        stopRealtime()
                        clearLocalState()
                    }
                    // Aktualisieren der Sitzung scheiterte (z. B. kein Netz): angemeldet bleiben, später erneut versuchen.
                    else -> if (client.auth.currentSessionOrNull() != null) _authState.value = AuthState.SIGNED_IN
                }
            }
        }
    }

    fun handleDeepLink(intent: Intent?) {
        intent ?: return
        val data = intent.data ?: return
        if (data.scheme == SupabaseConfig.AUTH_SCHEME && data.host == "join") {
            val code = data.pathSegments.firstOrNull()?.uppercase()
            if (!code.isNullOrBlank()) pendingJoinCode.value = code
            return
        }
        if (data.scheme == SupabaseConfig.AUTH_SCHEME && data.host == SupabaseConfig.AUTH_HOST) {
            try {
                client.handleDeeplinks(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Anmelde-Link nicht verarbeitet: $e")
                _message.value = "Der Anmelde-Link ist ungültig oder abgelaufen. Bitte einen neuen anfordern."
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearInviteCode() {
        _inviteCode.value = null
    }

    // ---------------------------------------------------------------- Konto

    fun sendMagicLink(email: String) = run("Link konnte nicht gesendet werden") {
        val mail = email.trim()
        client.auth.signInWith(OTP, redirectUrl = "${SupabaseConfig.AUTH_SCHEME}://${SupabaseConfig.AUTH_HOST}") {
            this.email = mail
            createUser = true
        }
        _linkSentTo.value = mail
    }

    fun resetLinkSent() {
        _linkSentTo.value = null
    }

    fun saveProfile(name: String, color: String, spokenName: String?) = run("Profil konnte nicht gespeichert werden") {
        val uid = client.auth.currentUserOrNull()?.id ?: return@run
        val profile = Profile(uid, name.trim(), spokenName?.trim()?.ifEmpty { null }, color)
        client.postgrest.from("profiles").upsert(profile)
        _profile.value = profile
    }

    fun signOut() = run("Abmelden fehlgeschlagen") {
        // Freigabe vorher beenden, damit keine Position zurückbleibt.
        runCatching { client.postgrest.rpc("stop_share") }
        client.auth.signOut()
    }

    fun deleteAccount() = run("Konto konnte nicht gelöscht werden") {
        client.postgrest.rpc("delete_account")
        client.auth.signOut()
    }

    // ---------------------------------------------------------------- Freunde

    fun createInvite() = run("Einladung konnte nicht erstellt werden") {
        _inviteCode.value = client.postgrest.rpc("create_invite").decodeAs<String>()
    }

    fun redeemInvite(code: String) = run("Einladung konnte nicht eingelöst werden") {
        val result = client.postgrest.rpc("redeem_invite", buildJsonObject { put("p_code", code.trim()) })
            .decodeAsOrNull<String>()
        if (result == null) {
            _message.value = "Dieser Code ist ungültig, abgelaufen oder schon benutzt."
        } else {
            _message.value = "Ihr seid jetzt Freunde."
            refreshFriends()
        }
    }

    fun removeFriend(id: String) = run("Freund konnte nicht entfernt werden") {
        client.postgrest.rpc("remove_friend", buildJsonObject { put("p_friend", id) })
        refreshFriends()
        refreshShare()
    }

    fun blockFriend(id: String) = run("Blockieren fehlgeschlagen") {
        client.postgrest.rpc("block_friend", buildJsonObject { put("p_friend", id) })
        refreshFriends()
        refreshShare()
    }

    fun unblockFriend(id: String) = run("Aufheben fehlgeschlagen") {
        client.postgrest.rpc("unblock_friend", buildJsonObject { put("p_friend", id) })
        refreshFriends()
    }

    // ---------------------------------------------------------------- Teilen

    /** Schaltet das automatische Teilen beim Aufzeichnen ein oder aus. Aus beendet eine laufende Freigabe. */
    fun setAutoShare(on: Boolean) {
        sharePrefs?.autoShare = on
        _autoShare.value = on
        if (!on && _share.value.active) stopShare()
    }

    /** Teilen mit einem einzelnen Freund ein- oder ausschalten. Wirkt sofort und gilt auch für spätere Aufzeichnungen. */
    fun setShareWith(friendId: String, enabled: Boolean) {
        val next = if (enabled) _excluded.value - friendId else _excluded.value + friendId
        _excluded.value = next
        sharePrefs?.excluded = next
        if (_share.value.active) setShareTarget(friendId, enabled)
    }

    /**
     * Beginn der Aufzeichnung: teilt mit allen Freunden, die nicht ausgenommen sind (bis zum Stopp, höchstens 12 Stunden).
     * Ohne Anmeldung, ohne Freunde oder bei ausgeschaltetem automatischem Teilen passiert nichts. Fehler (z. B. kein Netz)
     * stören die Aufzeichnung nicht.
     */
    suspend fun startAutoShare() {
        if (!_autoShare.value || _authState.value != AuthState.SIGNED_IN) return
        try {
            refreshFriends()
            val ids = ShareTargets.select(_friends.value, _excluded.value)
            if (ids.isEmpty()) return
            client.postgrest.rpc(
                "start_share",
                buildJsonObject {
                    put("p_hours", AUTO_SHARE_HOURS)
                    put("p_friends", JsonArray(ids.map { JsonPrimitive(it) }))
                },
            )
            refreshShare()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Automatisches Teilen nicht gestartet: $e")
        }
    }

    /** Nimmt Freunde in die laufende Freigabe auf, die erst nach dem Start dazukamen (z. B. durch eine Einladung). */
    private suspend fun syncAutoTargets() {
        val share = _share.value
        if (!share.active || !_autoShare.value) return
        val missing = ShareTargets.missing(ShareTargets.select(_friends.value, _excluded.value), share.friendIds)
        if (missing.isEmpty()) return
        for (id in missing) {
            try {
                client.postgrest.rpc(
                    "set_share_target",
                    buildJsonObject {
                        put("p_friend", id)
                        put("p_enabled", true)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Freund nicht in die Freigabe aufgenommen: $e")
            }
        }
        refreshShare()
    }

    fun stopShare() = run("Teilen konnte nicht beendet werden") {
        client.postgrest.rpc("stop_share")
        _share.value = ShareState()
    }

    /** Wie [stopShare], aber ohne Meldung und aus dem Tracking-Dienst aufrufbar. */
    suspend fun stopShareQuietly() {
        if (!_share.value.active) return
        try {
            client.postgrest.rpc("stop_share")
        } catch (e: Exception) {
            Log.w(TAG, "stop_share fehlgeschlagen: $e")
        }
        _share.value = ShareState()
    }

    fun setShareTarget(friendId: String, enabled: Boolean) = run("Freigabe konnte nicht geändert werden") {
        client.postgrest.rpc(
            "set_share_target",
            buildJsonObject {
                put("p_friend", friendId)
                put("p_enabled", enabled)
            },
        )
        refreshShare()
    }

    /** Sendet die eigene Position. Wirft bei Fehlern, damit der Aufrufer (Tracking-Dienst) reagieren kann. */
    suspend fun updateLocation(
        lat: Double, lon: Double, alt: Double?, speed: Double?, heading: Double?, accuracy: Double?, state: String,
    ) {
        client.postgrest.rpc(
            "update_location",
            buildJsonObject {
                put("p_lat", lat)
                put("p_lon", lon)
                put("p_alt", alt)
                put("p_speed", speed)
                put("p_heading", heading)
                put("p_accuracy", accuracy)
                put("p_state", state)
            },
        )
    }

    /** Die Freigabe ist serverseitig beendet oder abgelaufen. */
    fun markShareEnded() {
        _share.value = ShareState()
    }

    // ---------------------------------------------------------------- Laden

    fun refresh() {
        scope.launch { refreshAll() }
    }

    private suspend fun refreshAll() {
        refreshProfile()
        refreshFriends()
        refreshShare()
    }

    private suspend fun refreshProfile() {
        val uid = client.auth.currentUserOrNull()?.id ?: return
        try {
            _profile.value = client.postgrest.from("profiles").select { filter { eq("id", uid) } }
                .decodeSingleOrNull<Profile>()
            _profileLoaded.value = true
        } catch (e: Exception) {
            Log.w(TAG, "Profil laden: $e")
        }
    }

    private suspend fun refreshFriends() {
        try {
            _friends.value = client.postgrest.rpc("list_friends").decodeList<Friend>()
        } catch (e: Exception) {
            Log.w(TAG, "Freunde laden: $e")
            return
        }
        syncAutoTargets()
    }

    private suspend fun refreshShare() {
        try {
            val sessions = client.postgrest.from("share_sessions")
                .select { order("started_at", Order.DESCENDING); limit(5) }
                .decodeList<ShareSessionRow>()
            val now = System.currentTimeMillis()
            val active = sessions.firstOrNull { it.endedAt == null && parseInstantMs(it.expiresAt) > now }
            if (active == null) {
                _share.value = ShareState()
                return
            }
            val targets = client.postgrest.from("share_targets")
                .select { filter { eq("session_id", active.id) } }
                .decodeList<ShareTargetRow>()
            _share.value = ShareState(true, active.id, parseInstantMs(active.expiresAt), targets.map { it.friendId }.toSet())
        } catch (e: Exception) {
            Log.w(TAG, "Freigabe laden: $e")
        }
    }

    private fun clearLocalState() {
        _profile.value = null
        _profileLoaded.value = false
        _friends.value = emptyList()
        _share.value = ShareState()
        _live.value = emptyMap()
        _inviteCode.value = null
    }

    // ---------------------------------------------------------------- Realtime

    private fun startRealtime() {
        liveJob?.cancel()
        liveJob = scope.launch {
            while (isActive) {
                val channel = client.channel("live-friends")
                try {
                    val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "live_locations" }
                    channel.subscribe(blockUntilSubscribed = true)
                    loadLiveSnapshot()
                    changes.collect { handleChange(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Realtime: $e")
                } finally {
                    runCatching { client.realtime.removeChannel(channel) }
                }
                delay(5_000)
            }
        }
    }

    private fun stopRealtime() {
        liveJob?.cancel()
        liveJob = null
    }

    private suspend fun loadLiveSnapshot() {
        val me = client.auth.currentUserOrNull()?.id
        val rows = client.postgrest.from("live_locations").select().decodeList<LiveRow>()
        _live.value = rows.filter { it.userId != me }.associateBy { it.userId }
    }

    private fun handleChange(action: PostgresAction) {
        val me = client.auth.currentUserOrNull()?.id
        when (action) {
            is PostgresAction.Insert -> upsertLive(action.decodeRecord<LiveRow>(), me)
            is PostgresAction.Update -> upsertLive(action.decodeRecord<LiveRow>(), me)
            is PostgresAction.Delete -> action.oldRecord["user_id"]?.jsonPrimitive?.contentOrNull?.let { id ->
                _live.value = _live.value - id
            }
            else -> Unit
        }
    }

    private fun upsertLive(row: LiveRow, me: String?) {
        if (row.userId == me) return
        _live.value = _live.value + (row.userId to row)
    }

    // ---------------------------------------------------------------- Hilfen

    private fun run(errorText: String, block: suspend () -> Unit) {
        scope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$errorText: $e")
                _message.value = describe(e, errorText)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Übersetzt bekannte Fehlercodes der Datenbankfunktionen in verständliche Texte. */
    private fun describe(e: Exception, fallback: String): String {
        val text = e.message.orEmpty()
        return when {
            "too_many_attempts" in text -> "Zu viele Fehlversuche. Bitte in einer Stunde noch einmal versuchen."
            "too_many_invites" in text -> "Zu viele offene Einladungen. Bitte erst einlösen oder ablaufen lassen."
            "no_active_share" in text -> "Es läuft keine Freigabe."
            "rate limit" in text.lowercase() || "over_email_send_rate_limit" in text ->
                "Zu viele E-Mails angefordert. Bitte kurz warten."
            "Unable to resolve host" in text || "timeout" in text.lowercase() || "Failed to connect" in text ->
                "Keine Verbindung. Bitte Netz prüfen."
            // Alpha: den technischen Grund kurz mit anzeigen, damit Tester ihn melden können.
            text.isNotBlank() -> "$fallback\n(${text.lineSequence().first().take(140)})"
            else -> fallback
        }
    }
}
