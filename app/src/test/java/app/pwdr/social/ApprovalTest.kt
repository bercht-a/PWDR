package app.pwdr.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalTest {
    @Test fun recognizesServerRefusal() {
        assertTrue(ApprovalLogic.isNotApproved("""{"code":"PT403","message":"not_approved"}"""))
        assertTrue(ApprovalLogic.isNotApproved("not_approved"))
        assertFalse(ApprovalLogic.isNotApproved("Unable to resolve host"))
        assertFalse(ApprovalLogic.isNotApproved(""))
    }

    @Test fun stateFromStatusRows() {
        val pending = ApprovalLogic.fromRows(listOf(MyStatus(approved = false)))
        assertTrue(pending.known)
        assertFalse(pending.approved)

        val admin = ApprovalLogic.fromRows(listOf(MyStatus(approved = true, isAdmin = true, pendingCount = 3)))
        assertTrue(admin.approved && admin.isAdmin)
        assertEquals(3, admin.pendingCount)

        val user = ApprovalLogic.fromRows(listOf(MyStatus(approved = true, isAdmin = false, pendingCount = 5)))
        assertEquals("Zahl ist nur für Admins", 0, user.pendingCount)
        assertFalse(user.isAdmin)
    }

    @Test fun noRowMeansNotApproved() {
        val s = ApprovalLogic.fromRows(emptyList())
        assertTrue(s.known)
        assertFalse(s.approved)
    }

    @Test fun adminFlagNeedsApproval() {
        val s = ApprovalLogic.fromRows(listOf(MyStatus(approved = false, isAdmin = true, pendingCount = 2)))
        assertFalse(s.isAdmin)
    }

    @Test fun unknownStateStartsApprovedSoNothingFlashes() {
        val s = ApprovalState()
        assertFalse(s.known)
        assertTrue(s.approved)
    }

    @Test fun errorsMapToStates() {
        assertFalse(ApprovalLogic.fromError("not_approved")!!.approved)
        // Backend ohne 0007: wie bisher weiterarbeiten
        val legacy = ApprovalLogic.fromError("PGRST202 Could not find the function public.my_status")!!
        assertTrue(legacy.approved && legacy.known)
        // Netzfehler: unklar, Stand behalten
        assertNull(ApprovalLogic.fromError("Unable to resolve host"))
    }

    @Test fun texts() {
        assertEquals("1 Konto wartet auf Freigabe", ApprovalLogic.waitingText(1))
        assertEquals("3 Konten warten auf Freigabe", ApprovalLogic.waitingText(3))
        assertEquals(5, ApprovalLogic.badge(2, 3))
        assertTrue(ApprovalLogic.PENDING_TEXT.contains("Neu prüfen"))
    }
}
