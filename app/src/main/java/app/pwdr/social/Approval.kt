package app.pwdr.social

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Freigabe neuer Konten (0007_approval.sql, PLAN.md 5g).

/** Zeile aus my_status(). */
@Serializable
data class MyStatus(
    val approved: Boolean = false,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    /** Nur für Admins gefüllt: wartende Konten mit bestätigter E-Mail-Adresse. */
    @SerialName("pending_count") val pendingCount: Int = 0,
)

/** Zeile aus admin_list_pending(). */
@Serializable
data class PendingAccount(
    @SerialName("user_id") val userId: String,
    val email: String = "",
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * Stand der Freigabe dieses Kontos. [approved] ist true, solange nichts anderes bekannt ist (kein Aufblitzen der Warteseite)
 * und wenn das Backend die Freigabe noch nicht kennt (0007 nicht eingespielt).
 */
data class ApprovalState(
    val known: Boolean = false,
    val approved: Boolean = true,
    val isAdmin: Boolean = false,
    val pendingCount: Int = 0,
)

object ApprovalLogic {
    /** Der Server hat die Anfrage gesperrt, weil das Konto noch nicht freigegeben ist (Fehler not_approved, HTTP 403). */
    fun isNotApproved(errorText: String): Boolean = "not_approved" in errorText || "PT403" in errorText

    /**
     * Wertet die Antwort von my_status() aus. Keine Zeile heisst: nicht freigegeben (zum Beispiel ohne gültige Anmeldung).
     */
    fun fromRows(rows: List<MyStatus>): ApprovalState {
        val s = rows.firstOrNull() ?: return ApprovalState(known = true, approved = false)
        // Die Zahl wartender Konten ist nur für Admins gedacht.
        return ApprovalState(known = true, approved = s.approved, isAdmin = s.approved && s.isAdmin, pendingCount = if (s.isAdmin) s.pendingCount.coerceAtLeast(0) else 0)
    }

    /** Wie ein Fehler beim Abfragen des Stands zu deuten ist: null = unklar (Netz), Stand behalten. */
    fun fromError(errorText: String): ApprovalState? = when {
        isNotApproved(errorText) -> ApprovalState(known = true, approved = false)
        VisiblePeople.backendMissing(errorText) -> ApprovalState(known = true, approved = true)
        else -> null
    }

    /** Anzeige für den roten Zähler am Tab: wartende Konten (Admin) plus Einladungen. */
    fun badge(pendingAccounts: Int, invitations: Int): Int = pendingAccounts + invitations

    /** "1 Konto wartet" oder "3 Konten warten". */
    fun waitingText(n: Int): String = if (n == 1) "1 Konto wartet auf Freigabe" else "$n Konten warten auf Freigabe"

    const val PENDING_TITLE = "Dein Konto wartet auf Freigabe"
    const val PENDING_TEXT =
        "Der Entwickler muss dein Konto einmal freigeben, bevor du Freunde, Gruppen und Meldungen nutzen kannst. " +
            "Aufzeichnen, Karte und Statistik funktionieren schon jetzt. Sag Bescheid, dass du dich angemeldet hast, " +
            "und tippe dann auf \"Neu prüfen\"."
}
