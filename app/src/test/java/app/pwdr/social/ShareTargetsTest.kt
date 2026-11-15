package app.pwdr.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTargetsTest {
    private fun f(id: String, status: String = "accepted") = Friend(id, id.uppercase(), null, "#fff", status)

    @Test
    fun selectsAllAcceptedFriendsExceptExcluded() {
        val friends = listOf(f("a"), f("b"), f("c"))
        assertEquals(listOf("a", "b", "c"), ShareTargets.select(friends, emptySet()))
        assertEquals(listOf("a", "c"), ShareTargets.select(friends, setOf("b")))
    }

    @Test
    fun blockedFriendsAreNeverSelected() {
        val friends = listOf(f("a"), f("d", "blocked"))
        assertEquals(listOf("a"), ShareTargets.select(friends, emptySet()))
    }

    @Test
    fun noFriendsMeansNoTargets() {
        assertTrue(ShareTargets.select(emptyList(), emptySet()).isEmpty())
        assertTrue(ShareTargets.select(listOf(f("a")), setOf("a")).isEmpty())
    }

    @Test
    fun newFriendDuringSharingIsAddedButExcludedOneIsNot() {
        val selected = ShareTargets.select(listOf(f("a"), f("b"), f("neu"), f("stumm")), setOf("stumm"))
        assertEquals(listOf("neu"), ShareTargets.missing(selected, setOf("a", "b")))
        assertTrue(ShareTargets.missing(selected, setOf("a", "b", "neu")).isEmpty())
    }
}
