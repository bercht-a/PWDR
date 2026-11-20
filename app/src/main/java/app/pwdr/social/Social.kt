package app.pwdr.social

import android.content.Context
import android.content.Intent
import android.util.Log
import app.pwdr.tracking.TrackingStore
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
    private const val REPORT_RADIUS_M = 30_000
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

    // Gruppen (PLAN.md 5f): meine Gruppen, deren Mitglieder, Einladungen von Freunden, wer mich sieht, und die Vereinigung
    // aus Freunden und Gruppenmitgliedern, die auf der Karte erscheinen kann.
    private val _groups = MutableStateFlow<List<GroupRow>>(emptyList())
    val groups: StateFlow<List<GroupRow>> = _groups.asStateFlow()

    private val _groupMembers = MutableStateFlow<Map<String, List<GroupMemberRow>>>(emptyMap())

    /** Mitglieder je Gruppen-ID (nur aktive Gruppen). */
    val groupMembers: StateFlow<Map<String, List<GroupMemberRow>>> = _groupMembers.asStateFlow()

    private val _invitations = MutableStateFlow<List<GroupInvitationRow>>(emptyList())

    /** Einladungen von Freunden in Gruppen, die ich noch annehmen oder ablehnen kann. */
    val invitations: StateFlow<List<GroupInvitationRow>> = _invitations.asStateFlow()

    private val _viewers = MutableStateFlow<List<ViewerRow>>(emptyList())

    /** Wer mich gerade sieht (Freunde und Gruppenmitglieder), nur während einer Freigabe gefüllt. */
    val viewers: StateFlow<List<ViewerRow>> = _viewers.asStateFlow()

    private val _people = MutableStateFlow<List<Person>>(emptyList())

    /** Freunde und Gruppenmitglieder ohne Doppelte, die auf Karte und in der Sprachausgabe vorkommen. */
    val people: StateFlow<List<Person>> = _people.asStateFlow()

    private val _live = MutableStateFlow<Map<String, LiveRow>>(emptyMap())
    val live: StateFlow<Map<String, LiveRow>> = _live.asStateFlow()

    private val _diag = MutableStateFlow(ShareDiag())

    /** Technischer Stand zur Fehlersuche (Tab "Freunde"). */
    val diag: StateFlow<ShareDiag> = _diag.asStateFlow()

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
    private var groupsJob: Job? = null
    private var lastPeopleRefreshMs = 0L
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
                        startGroupsLoop()
                    }
                    is SessionStatus.Initializing -> _authState.value = AuthState.LOADING
                    is SessionStatus.NotAuthenticated -> {
                        _authState.value = AuthState.SIGNED_OUT
                        stopRealtime()
                        stopGroupsLoop()
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
        if (!_autoShare.value) {
            _diag.value = _diag.value.copy(startNote = "automatisches Teilen ist aus")
            return
        }
        if (_authState.value != AuthState.SIGNED_IN) {
            _diag.value = _diag.value.copy(startNote = "nicht angemeldet")
            return
        }
        try {
            refreshFriends()
            loadGroups()
            val ids = ShareTargets.select(_friends.value, _excluded.value)
            // Gruppen brauchen keine Freigabe pro Freund, die Sicht ergibt sich aus der Mitgliedschaft. Es genügt also eine Gruppe.
            if (ids.isEmpty() && !VisiblePeople.sharesWithAnyGroup(_groups.value, System.currentTimeMillis())) {
                _diag.value = _diag.value.copy(startNote = "keine Freunde oder Gruppen ausgewählt")
                return
            }
            _diag.value = _diag.value.copy(startNote = null)
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
            _diag.value = _diag.value.copy(startNote = "Fehler: ${e.message.orEmpty().lineSequence().first().take(100)}")
        }
    }

    /** Position wurde gesendet. */
    fun reportSent(nowMs: Long = System.currentTimeMillis()) {
        _diag.value = _diag.value.copy(lastSentMs = nowMs, sentCount = _diag.value.sentCount + 1, lastError = null)
    }

    fun reportSendError(text: String) {
        _diag.value = _diag.value.copy(lastError = text.lineSequence().firstOrNull().orEmpty().take(120))
    }

    fun reportSkip(note: String?) {
        if (_diag.value.skipNote != note) _diag.value = _diag.value.copy(skipNote = note)
    }

    private fun setRealtime(text: String) {
        if (_diag.value.realtime != text) _diag.value = _diag.value.copy(realtime = text)
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
        refreshGroups()
        refreshShare()
        refreshViewers()
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
        recomputePeople()
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
        _groups.value = emptyList()
        _groupMembers.value = emptyMap()
        _invitations.value = emptyList()
        _viewers.value = emptyList()
        _people.value = emptyList()
        _share.value = ShareState()
        _live.value = emptyMap()
        _reports.value = emptyList()
        _inviteCode.value = null
    }

    // ---------------------------------------------------------------- Realtime

    private fun startRealtime() {
        liveJob?.cancel()
        liveJob = scope.launch {
            while (isActive) {
                val channel = client.channel("live-friends")
                try {
                    setRealtime("verbindet…")
                    val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "live_locations" }
                    channel.subscribe(blockUntilSubscribed = true)
                    setRealtime("verbunden")
                    loadLiveSnapshot()
                    changes.collect { handleChange(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Realtime: $e")
                    setRealtime("Fehler (${e.message.orEmpty().lineSequence().first().take(80)})")
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
        // Neue Freunde oder Gruppenmitglieder sind erst nach dem Auffrischen der Listen mit Namen bekannt.
        if (_people.value.none { it.friend.friendId == row.userId }) requestPeopleRefresh()
    }

    // ---------------------------------------------------------------- Gruppen

    private const val GROUPS_POLL_MS = 60_000L
    private const val PEOPLE_REFRESH_MIN_MS = 30_000L

    private fun startGroupsLoop() {
        groupsJob?.cancel()
        groupsJob = scope.launch {
            while (isActive) {
                delay(GROUPS_POLL_MS)
                refreshGroups()
                if (_share.value.active) refreshViewers()
            }
        }
    }

    private fun stopGroupsLoop() {
        groupsJob?.cancel()
        groupsJob = null
    }

    /** Lädt Gruppen, Mitglieder und Einladungen. Fehlt die Funktion im Backend (0006 nicht eingespielt), bleibt alles leer. */
    private suspend fun loadGroups() {
        val now = System.currentTimeMillis()
        try {
            val groups = client.postgrest.rpc("list_groups").decodeList<GroupRow>()
            val members = HashMap<String, List<GroupMemberRow>>()
            for (g in groups) {
                if (!g.isActive(now)) continue
                try {
                    members[g.groupId] = client.postgrest.rpc("list_group_members", buildJsonObject { put("p_group", g.groupId) })
                        .decodeList<GroupMemberRow>()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Mitglieder laden: $e")
                    _groupMembers.value[g.groupId]?.let { members[g.groupId] = it }  // letzten Stand behalten
                }
            }
            val invitations = client.postgrest.rpc("list_group_invitations").decodeList<GroupInvitationRow>()
            _groups.value = groups
            _groupMembers.value = members
            _invitations.value = invitations
            _diag.value = _diag.value.copy(groupCount = groups.size, groupMembers = members.values.sumOf { it.size }, groupsNote = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val text = e.message.orEmpty()
            if (VisiblePeople.backendMissing(text)) {
                _groups.value = emptyList()
                _groupMembers.value = emptyMap()
                _invitations.value = emptyList()
                _diag.value = _diag.value.copy(groupCount = 0, groupMembers = 0, groupsNote = "im Backend nicht eingespielt (0006)")
            } else {
                Log.w(TAG, "Gruppen laden: $e")
                _diag.value = _diag.value.copy(groupsNote = "Fehler (${text.lineSequence().first().take(80)})")
            }
        }
        recomputePeople()
    }

    /** Wie [loadGroups] und startet das Teilen, falls gerade aufgezeichnet wird und eine Gruppe neu dazukam. */
    private suspend fun refreshGroups() {
        loadGroups()
        if (_share.value.active || !_autoShare.value || !TrackingStore.recording.value) return
        if (VisiblePeople.sharesWithAnyGroup(_groups.value, System.currentTimeMillis())) startAutoShare()
    }

    private suspend fun refreshViewers() {
        try {
            _viewers.value = client.postgrest.rpc("my_viewers").decodeList<ViewerRow>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!VisiblePeople.backendMissing(e.message.orEmpty())) Log.w(TAG, "Wer sieht mich: $e")
            _viewers.value = emptyList()
        }
    }

    private fun recomputePeople() {
        val me = client.auth.currentUserOrNull()?.id
        _people.value = VisiblePeople.merge(_friends.value, _groups.value, _groupMembers.value, me, System.currentTimeMillis())
    }

    /** Frischt Freunde und Gruppen auf, höchstens alle 30 Sekunden. */
    private fun requestPeopleRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastPeopleRefreshMs < PEOPLE_REFRESH_MIN_MS) return
        lastPeopleRefreshMs = now
        scope.launch {
            refreshFriends()
            loadGroups()
        }
    }

    /** Für die Oberfläche: Gruppen, Mitglieder und Einladungen neu laden. */
    fun refreshGroupsNow() {
        scope.launch {
            refreshGroups()
            if (_share.value.active) refreshViewers()
        }
    }

    /** Gruppe erstellen, optional gleich Freunde einladen. [days] null = unbegrenzt. */
    fun createGroup(name: String, days: Int?, friendIds: List<String> = emptyList()) = run("Gruppe konnte nicht erstellt werden") {
        val id = client.postgrest.rpc(
            "create_group",
            buildJsonObject {
                put("p_name", name)
                put("p_days", days)
            },
        ).decodeAs<String>()
        if (friendIds.isNotEmpty()) inviteFriendsCall(id, friendIds)
        refreshGroups()
    }

    fun renameGroup(groupId: String, name: String) = groupCall("Umbenennen fehlgeschlagen", "rename_group", groupId) { put("p_name", name) }

    /** [days] null = unbegrenzt. */
    fun extendGroup(groupId: String, days: Int?) = groupCall("Verlängern fehlgeschlagen", "extend_group", groupId) { put("p_days", days) }

    fun deleteGroup(groupId: String) = groupCall("Löschen fehlgeschlagen", "delete_group", groupId) {}

    fun leaveGroup(groupId: String) = groupCall("Austreten fehlgeschlagen", "leave_group", groupId) {}

    fun removeMember(groupId: String, userId: String) = groupCall("Entfernen fehlgeschlagen", "remove_member", groupId) { put("p_user", userId) }

    fun setGroupSharing(groupId: String, on: Boolean) = groupCall("Teilen konnte nicht geändert werden", "set_group_sharing", groupId) { put("p_on", on) }

    fun setGroupAnnounce(groupId: String, on: Boolean) = groupCall("Ansagen konnten nicht geändert werden", "set_group_announce", groupId) { put("p_on", on) }

    fun inviteFriendsToGroup(groupId: String, friendIds: List<String>) = run("Einladen fehlgeschlagen") {
        val n = inviteFriendsCall(groupId, friendIds)
        if (n == 0) _message.value = "Niemand eingeladen (schon Mitglied, schon eingeladen oder kein Freund)."
        refreshGroups()
    }

    private suspend fun inviteFriendsCall(groupId: String, friendIds: List<String>): Int =
        client.postgrest.rpc(
            "invite_friends_to_group",
            buildJsonObject {
                put("p_group", groupId)
                put("p_friends", JsonArray(friendIds.map { JsonPrimitive(it) }))
            },
        ).decodeAs<Int>()

    fun acceptGroupInvitation(groupId: String) = groupCall("Beitreten fehlgeschlagen", "accept_group_invitation", groupId) {}

    fun declineGroupInvitation(groupId: String) = groupCall("Ablehnen fehlgeschlagen", "decline_group_invitation", groupId) {}

    fun cancelGroupInvitation(groupId: String, friendId: String) =
        groupCall("Zurückziehen fehlgeschlagen", "cancel_group_invitation", groupId) { put("p_friend", friendId) }

    private val _groupInviteCode = MutableStateFlow<Pair<String, String>?>(null)

    /** Zuletzt erzeugter Gruppen-Code als (Gruppen-ID, Code). */
    val groupInviteCode: StateFlow<Pair<String, String>?> = _groupInviteCode.asStateFlow()

    fun clearGroupInviteCode() {
        _groupInviteCode.value = null
    }

    fun createGroupInvite(groupId: String, maxUses: Int = 10) = run("Code konnte nicht erstellt werden") {
        val code = client.postgrest.rpc(
            "create_group_invite",
            buildJsonObject {
                put("p_group", groupId)
                put("p_max_uses", maxUses)
            },
        ).decodeAs<String>()
        _groupInviteCode.value = groupId to code
    }

    fun revokeGroupInvites(groupId: String) = groupCall("Widerrufen fehlgeschlagen", "revoke_group_invites", groupId) {}

    /** Offene Einladungen an Freunde (nur Ersteller). Leer bei Fehlern. */
    suspend fun loadPending(groupId: String): List<GroupPendingRow> = try {
        client.postgrest.rpc("list_group_pending", buildJsonObject { put("p_group", groupId) }).decodeList<GroupPendingRow>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Offene Einladungen laden: $e")
        emptyList()
    }

    /** Ruft eine Gruppen-Funktion mit p_group und weiteren Parametern auf und lädt danach Gruppen und Freigabe neu. */
    private fun groupCall(errorText: String, function: String, groupId: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        run(errorText) {
            client.postgrest.rpc(
                function,
                buildJsonObject {
                    put("p_group", groupId)
                    extra()
                },
            )
            refreshGroups()
            if (_share.value.active) refreshViewers()
        }

    // ---------------------------------------------------------------- Meldungen

    private val _reports = MutableStateFlow<List<ReportRow>>(emptyList())

    /** Meldungen im Umkreis der letzten Abfrage. */
    val reports: StateFlow<List<ReportRow>> = _reports.asStateFlow()

    /** Lädt die Meldungen im Umkreis. Ohne Anmeldung oder Netz bleibt die letzte Liste stehen. */
    suspend fun loadReports(lat: Double, lon: Double) {
        if (_authState.value != AuthState.SIGNED_IN) return
        try {
            _reports.value = client.postgrest.rpc(
                "list_reports",
                buildJsonObject {
                    put("p_lat", lat)
                    put("p_lon", lon)
                    put("p_radius_m", REPORT_RADIUS_M)
                },
            ).decodeList<ReportRow>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Meldungen laden: $e")
        }
    }

    /** Gibt null zurück, wenn es geklappt hat, sonst einen Text für die Anzeige. */
    suspend fun postReport(type: ReportType, lat: Double, lon: Double, text: String): String? = reportCall {
        client.postgrest.rpc(
            "post_report",
            buildJsonObject {
                put("p_type", type.key)
                put("p_lat", lat)
                put("p_lon", lon)
                put("p_text", text.trim().take(ReportsOnMap.MAX_TEXT).ifEmpty { null })
            },
        )
        loadReports(lat, lon)
    }

    suspend fun voteReport(id: String, vote: Int, lat: Double, lon: Double): String? = reportCall {
        client.postgrest.rpc("vote_report", buildJsonObject { put("p_id", id); put("p_vote", vote) })
        loadReports(lat, lon)
    }

    suspend fun deleteReport(id: String, lat: Double, lon: Double): String? = reportCall {
        client.postgrest.rpc("delete_report", buildJsonObject { put("p_id", id) })
        loadReports(lat, lon)
    }

    private suspend fun reportCall(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Meldung: $e")
        ReportsOnMap.errorText(e.message.orEmpty().ifBlank { e.toString() })
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
            "not_owner" in text -> "Das darf nur der Ersteller der Gruppe."
            "group_full" in text -> "Die Gruppe ist voll (höchstens 20 Mitglieder, offene Einladungen eingerechnet)."
            "group_expired" in text -> "Die Gruppe ist abgelaufen. Bitte zuerst verlängern."
            "too_many_groups" in text -> "Du hast schon 5 eigene Gruppen. Bitte erst eine löschen."
            "too_many_memberships" in text -> "Du bist schon in 10 Gruppen. Bitte erst eine verlassen."
            "invalid_name" in text -> "Der Gruppenname muss 1 bis 30 Zeichen lang sein."
            "no_invitation" in text -> "Diese Einladung gibt es nicht mehr."
            "not_a_member" in text -> "Du bist nicht Mitglied dieser Gruppe."
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
