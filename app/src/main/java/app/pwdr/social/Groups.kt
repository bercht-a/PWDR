package app.pwdr.social

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Zeilen der Gruppen-Funktionen aus 0006_groups.sql (PLAN.md 5f). Spaltennamen wie in der Datenbank.

/** Zeile aus list_groups(): eine Gruppe, in der ich Mitglied bin. */
@Serializable
data class GroupRow(
    @SerialName("group_id") val groupId: String,
    val name: String,
    /** owner oder member. */
    val role: String,
    @SerialName("member_count") val memberCount: Int = 1,
    /** Offene Einladungen an Freunde, nur für den Ersteller gefüllt. */
    @SerialName("pending_count") val pendingCount: Int = 0,
    @SerialName("owner_name") val ownerName: String = "",
    /** Null = unbegrenzt. */
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("share_location") val shareLocation: Boolean = true,
    val announce: Boolean = true,
) {
    val isOwner: Boolean get() = role == "owner"

    /** Ablauf in Millisekunden, null = unbegrenzt (oder nicht lesbar). */
    val expiresAtMs: Long? get() = expiresAt?.let { runCatching { parseInstantMs(it) }.getOrNull() }

    /** Läuft die Gruppe noch? Abgelaufene Gruppen bleiben sieben Tage in der Liste, geben aber keine Sicht mehr frei. */
    fun isActive(nowMs: Long): Boolean = expiresAtMs?.let { it > nowMs } ?: true
}

/** Zeile aus list_group_members(). [visible] heisst: Ich sehe diese Person gerade auf der Karte. */
@Serializable
data class GroupMemberRow(
    @SerialName("member_id") val memberId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("spoken_name") val spokenName: String? = null,
    val color: String = "#4CC9F0",
    val role: String = "member",
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("share_location") val shareLocation: Boolean = true,
    val visible: Boolean = false,
)

/** Zeile aus list_group_invitations(): Einladung eines Freundes an mich. */
@Serializable
data class GroupInvitationRow(
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String,
    @SerialName("inviter_name") val inviterName: String = "",
    @SerialName("member_count") val memberCount: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** Zeile aus list_group_pending(): offene Einladung an einen Freund (nur für den Ersteller). */
@Serializable
data class GroupPendingRow(
    @SerialName("friend_id") val friendId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** Zeile aus preview_group_invite(): Was man vor dem Beitritt sieht. */
@Serializable
data class GroupPreview(
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String,
    @SerialName("owner_name") val ownerName: String = "",
    @SerialName("member_count") val memberCount: Int = 1,
    @SerialName("is_full") val isFull: Boolean = false,
    @SerialName("already_member") val alreadyMember: Boolean = false,
)

/** Zeile aus my_viewers(): jemand, der mich gerade sieht, mit den Gruppen, über die das läuft. */
@Serializable
data class ViewerRow(
    @SerialName("viewer_id") val viewerId: String,
    @SerialName("display_name") val displayName: String,
    val color: String = "#4CC9F0",
    @SerialName("via_friend") val viaFriend: Boolean = false,
    @SerialName("group_names") val groupNames: List<String> = emptyList(),
)

/**
 * Eine Person, die auf der Karte erscheinen kann: Freund, Gruppenmitglied oder beides. [friend] trägt Name, Farbe und
 * Aussprache-Name, damit Karte und Sprachausgabe unverändert mit Freunden arbeiten können.
 */
data class Person(
    val friend: Friend,
    val isFriend: Boolean,
    /** Gruppen (nur aktive), über die man diese Person sieht. */
    val groupNames: List<String>,
    /** Annäherungs-Ansagen für diese Person: für Freunde immer, für reine Gruppenmitglieder nur bei Gruppen mit Ansagen. */
    val announce: Boolean,
)

object VisiblePeople {
    /**
     * Vereinigung aus Freunden und Mitgliedern aktiver Gruppen, ohne Doppelte und ohne mich selbst. Wer als Freund blockiert
     * ist, erscheint auch als Gruppenmitglied nie (der Server blendet das ebenfalls aus). Abgelaufene Gruppen zählen nicht.
     */
    fun merge(
        friends: List<Friend>,
        groups: List<GroupRow>,
        members: Map<String, List<GroupMemberRow>>,
        myId: String?,
        nowMs: Long,
    ): List<Person> {
        val blocked = friends.filter { it.status != "accepted" }.map { it.friendId }.toSet()
        val byId = LinkedHashMap<String, Person>()
        for (f in friends) {
            if (f.status != "accepted") continue
            byId[f.friendId] = Person(f, isFriend = true, groupNames = emptyList(), announce = true)
        }
        for (g in groups) {
            if (!g.isActive(nowMs)) continue
            for (m in members[g.groupId].orEmpty()) {
                if (m.memberId == myId || m.memberId in blocked) continue
                val existing = byId[m.memberId]
                byId[m.memberId] = if (existing != null) {
                    // Freunde bleiben angesagt, eine reine Gruppenperson wird es, sobald eine ihrer Gruppen Ansagen an hat.
                    existing.copy(groupNames = existing.groupNames + g.name, announce = existing.announce || g.announce)
                } else {
                    Person(
                        Friend(m.memberId, m.displayName, m.spokenName, m.color, "accepted"),
                        isFriend = false,
                        groupNames = listOf(g.name),
                        announce = g.announce,
                    )
                }
            }
        }
        return byId.values.sortedBy { it.friend.displayName.lowercase() }
    }

    /** Gibt es mindestens eine aktive Gruppe, in der ich meinen Standort teile? Dann muss beim Aufzeichnen geteilt werden. */
    fun sharesWithAnyGroup(groups: List<GroupRow>, nowMs: Long): Boolean = groups.any { it.isActive(nowMs) && it.shareLocation }

    /** Fehlt die Funktion im Backend (Migration 0006 noch nicht eingespielt)? PostgREST meldet dann PGRST202. */
    fun backendMissing(errorText: String): Boolean =
        "PGRST202" in errorText || "Could not find the function" in errorText || "schema cache" in errorText
}

/** Texte und Auswahlmöglichkeiten für die Gruppen-Oberfläche, ohne Android, damit sie sich testen lassen. */
object GroupTexts {
    const val MAX_NAME = 30
    const val MAX_MEMBERS = 20

    data class DurationOption(val label: String, val days: Int?)

    /** Tage bis zum nächsten 30. April (Saisonende), mindestens 1 und höchstens 366. */
    fun daysToSeasonEnd(today: java.time.LocalDate): Int {
        var end = java.time.LocalDate.of(today.year, 4, 30)
        if (end.isBefore(today)) end = end.plusYears(1)
        return java.time.temporal.ChronoUnit.DAYS.between(today, end).toInt().coerceIn(1, 366)
    }

    /** Laufzeiten beim Erstellen und Verlängern. Der Vorschlag (7 Tage) steht an dritter Stelle. */
    fun durationOptions(today: java.time.LocalDate): List<DurationOption> = listOf(
        DurationOption("1 Tag", 1),
        DurationOption("3 Tage", 3),
        DurationOption("7 Tage (empfohlen)", 7),
        DurationOption("Bis Saisonende (30. April)", daysToSeasonEnd(today)),
        DurationOption("Unbegrenzt", null),
    )

    const val DEFAULT_DURATION_INDEX = 2

    fun memberCount(n: Int): String = if (n == 1) "1 Mitglied" else "$n Mitglieder"

    /** "unbegrenzt", "noch 3 Tage (bis 27.11.)", "noch 5 Std." oder "abgelaufen". */
    fun expiry(group: GroupRow, nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val end = group.expiresAtMs ?: return "unbegrenzt"
        val left = end - nowMs
        if (left <= 0) return "abgelaufen"
        val date = java.time.Instant.ofEpochMilli(end).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("d.M."))
        val hours = left / 3_600_000L
        val days = left / 86_400_000L
        return when {
            left < 3_600_000L -> "läuft in weniger als 1 Std. ab"
            days < 1 -> "noch $hours Std. (bis $date)"
            else -> "noch $days ${if (days == 1L) "Tag" else "Tage"} (bis $date)"
        }
    }

    /** Neues Mitglied: in den letzten 24 Stunden beigetreten. */
    fun isNew(joinedAt: String?, nowMs: Long): Boolean {
        val t = joinedAt?.let { runCatching { parseInstantMs(it) }.getOrNull() } ?: return false
        return nowMs - t in 0..86_400_000L
    }

    /** Statuszeile eines Mitglieds in der Mitgliederliste. */
    fun memberStatus(m: GroupMemberRow, isMe: Boolean): String = when {
        isMe && !m.shareLocation -> "Du, teilst in dieser Gruppe nicht"
        isMe -> "Du"
        m.visible -> "auf der Karte"
        !m.shareLocation -> "teilt in dieser Gruppe nicht"
        else -> "zeichnet gerade nicht auf"
    }

    /** Wer mich gerade sieht, eine Zeile je Person: "Hugo (Freund, Wochenende Arosa)". */
    fun viewerLines(viewers: List<ViewerRow>): List<String> = viewers.sortedBy { it.displayName.lowercase() }.map { v ->
        val via = buildList {
            if (v.viaFriend) add("Freund")
            addAll(v.groupNames)
        }
        if (via.isEmpty()) v.displayName else "${v.displayName} (${via.joinToString(", ")})"
    }

    fun inviteShareText(code: String, groupName: String, maxUses: Int): String =
        "Komm in meine PWDR-Gruppe \"$groupName\"! Code: $code (24 Stunden gültig, höchstens $maxUses Beitritte) oder Link: pwdr://group/$code"

    /** Der Satz, der vor jedem Beitritt steht (Datenschutz, PLAN.md 5f). */
    const val JOIN_NOTICE =
        "Alle Mitglieder sehen deinen Standort, solange du aufzeichnest und das Teilen in der Gruppe an ist. " +
            "Du siehst alle, die gerade aufzeichnen. Du kannst das Teilen jederzeit ausschalten oder die Gruppe verlassen."
}

