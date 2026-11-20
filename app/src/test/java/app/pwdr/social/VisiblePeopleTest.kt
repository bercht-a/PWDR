package app.pwdr.social

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisiblePeopleTest {
    private val now = 1_800_000_000_000L
    private val me = "me"

    private fun friend(id: String, name: String = id.uppercase(), status: String = "accepted") = Friend(id, name, null, "#111111", status)
    private fun group(id: String, name: String = "G$id", expires: String? = null, announce: Boolean = true, share: Boolean = true) =
        GroupRow(id, name, "member", 3, 0, "Owner", expires, share, announce)
    private fun member(id: String, name: String = id.uppercase()) = GroupMemberRow(id, name, "Aussprache $name", "#222222")

    @Test fun groupMembersAppearWithoutBeingFriends() {
        val people = VisiblePeople.merge(emptyList(), listOf(group("1")), mapOf("1" to listOf(member("a"), member("b"))), me, now)
        assertEquals(listOf("a", "b"), people.map { it.friend.friendId })
        assertTrue(people.none { it.isFriend })
        assertEquals("accepted", people[0].friend.status)
        assertEquals("Aussprache A", people[0].friend.spokenName)
    }

    @Test fun myselfIsNeverListed() {
        val people = VisiblePeople.merge(emptyList(), listOf(group("1")), mapOf("1" to listOf(member(me), member("a"))), me, now)
        assertEquals(listOf("a"), people.map { it.friend.friendId })
    }

    @Test fun friendAndMemberAppearOnceWithGroupName() {
        val people = VisiblePeople.merge(listOf(friend("a", "Anna")), listOf(group("1", "Arosa")), mapOf("1" to listOf(member("a", "Anna"))), me, now)
        assertEquals(1, people.size)
        assertTrue(people[0].isFriend)
        assertEquals(listOf("Arosa"), people[0].groupNames)
    }

    @Test fun blockedFriendIsHiddenEvenInAGroup() {
        val people = VisiblePeople.merge(listOf(friend("a", status = "blocked")), listOf(group("1")), mapOf("1" to listOf(member("a"), member("b"))), me, now)
        assertEquals(listOf("b"), people.map { it.friend.friendId })
    }

    @Test fun expiredGroupGivesNoPeople() {
        val past = "2020-01-01T00:00:00Z"
        val people = VisiblePeople.merge(emptyList(), listOf(group("1", expires = past)), mapOf("1" to listOf(member("a"))), me, now)
        assertTrue(people.isEmpty())
    }

    @Test fun personInTwoGroupsListsBothNames() {
        val people = VisiblePeople.merge(
            emptyList(), listOf(group("1", "Eins"), group("2", "Zwei")),
            mapOf("1" to listOf(member("a")), "2" to listOf(member("a"), member("b"))), me, now,
        )
        assertEquals(listOf("Eins", "Zwei"), people.first { it.friend.friendId == "a" }.groupNames)
        assertEquals(listOf("Zwei"), people.first { it.friend.friendId == "b" }.groupNames)
    }

    @Test fun announcementsFollowTheGroupSwitchButNeverMuteFriends() {
        val people = VisiblePeople.merge(
            listOf(friend("f")),
            listOf(group("1", announce = false), group("2", announce = true)),
            mapOf("1" to listOf(member("a"), member("f"), member("c")), "2" to listOf(member("c"))), me, now,
        )
        assertFalse("nur in einer stummen Gruppe", people.first { it.friend.friendId == "a" }.announce)
        assertTrue("Freund bleibt angesagt", people.first { it.friend.friendId == "f" }.announce)
        assertTrue("eine Gruppe mit Ansagen genügt", people.first { it.friend.friendId == "c" }.announce)
    }

    @Test fun sortedByNameIgnoringCase() {
        val people = VisiblePeople.merge(listOf(friend("x", "zoe")), listOf(group("1")), mapOf("1" to listOf(member("a", "Anna"), member("b", "bernd"))), me, now)
        assertEquals(listOf("Anna", "bernd", "zoe"), people.map { it.friend.displayName })
    }

    @Test fun membersOfUnknownGroupAreIgnored() {
        val people = VisiblePeople.merge(emptyList(), emptyList(), mapOf("x" to listOf(member("a"))), me, now)
        assertTrue(people.isEmpty())
    }

    // ---------------------------------------------------------------- Teilen mit Gruppen
    @Test fun sharingRequiresAnActiveGroupWithSharingOn() {
        assertFalse(VisiblePeople.sharesWithAnyGroup(emptyList(), now))
        assertTrue(VisiblePeople.sharesWithAnyGroup(listOf(group("1")), now))
        assertFalse(VisiblePeople.sharesWithAnyGroup(listOf(group("1", share = false)), now))
        assertFalse(VisiblePeople.sharesWithAnyGroup(listOf(group("1", expires = "2020-01-01T00:00:00Z")), now))
        assertTrue(VisiblePeople.sharesWithAnyGroup(listOf(group("1", share = false), group("2")), now))
    }

    @Test fun expiryParsing() {
        assertNull(group("1").expiresAtMs)
        assertTrue(group("1").isActive(now))
        val g = group("1", expires = "2026-12-01T10:00:00+00:00")
        assertNotNull(g.expiresAtMs)
        assertTrue(g.isActive(g.expiresAtMs!! - 1))
        assertFalse(g.isActive(g.expiresAtMs!!))
        assertNull(group("1", expires = "kaputt").expiresAtMs)
        assertTrue("nicht lesbarer Ablauf gilt als unbegrenzt", group("1", expires = "kaputt").isActive(now))
    }

    @Test fun missingBackendIsRecognised() {
        assertTrue(VisiblePeople.backendMissing("Could not find the function public.list_groups without parameters in the schema cache"))
        assertTrue(VisiblePeople.backendMissing("PGRST202"))
        assertFalse(VisiblePeople.backendMissing("not_authenticated"))
        assertFalse(VisiblePeople.backendMissing("Unable to resolve host"))
    }

    // ---------------------------------------------------------------- JSON wie von PostgREST
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun decodesGroupRows() {
        val text = """[{"group_id":"g1","name":"Arosa","role":"owner","member_count":3,"pending_count":1,"owner_name":"Greta",
            "expires_at":"2026-11-27T10:00:00+00:00","share_location":true,"announce":false}]"""
        val g = json.decodeFromString<List<GroupRow>>(text).single()
        assertEquals("Arosa", g.name)
        assertTrue(g.isOwner)
        assertEquals(1, g.pendingCount)
        assertFalse(g.announce)
        assertNotNull(g.expiresAtMs)
    }

    @Test fun decodesUnlimitedGroupAndMissingColumns() {
        val g = json.decodeFromString<List<GroupRow>>("""[{"group_id":"g","name":"X","role":"member","expires_at":null}]""").single()
        assertNull(g.expiresAt)
        assertTrue(g.shareLocation)
        assertEquals(1, g.memberCount)
    }

    @Test fun decodesMembersInvitationsViewersAndPreview() {
        val m = json.decodeFromString<List<GroupMemberRow>>(
            """[{"member_id":"u1","display_name":"Greta","spoken_name":null,"color":"#FF5A5F","role":"owner","joined_at":"2026-11-20T10:00:00+00:00","share_location":true,"visible":true}]""",
        ).single()
        assertEquals("Greta", m.displayName)
        assertTrue(m.visible)
        assertNull(m.spokenName)

        val i = json.decodeFromString<List<GroupInvitationRow>>(
            """[{"group_id":"g","group_name":"Arosa","inviter_name":"Greta","member_count":4,"created_at":"2026-11-20T10:00:00+00:00","expires_at":"2026-11-27T10:00:00+00:00"}]""",
        ).single()
        assertEquals("Greta", i.inviterName)
        assertEquals(4, i.memberCount)

        val v = json.decodeFromString<List<ViewerRow>>(
            """[{"viewer_id":"u2","display_name":"Hugo","color":"#4CC9F0","via_friend":true,"group_names":["Arosa","Saison"]},
                {"viewer_id":"u3","display_name":"Iris","color":"#4CC9F0","via_friend":false,"group_names":[]}]""",
        )
        assertEquals(listOf("Arosa", "Saison"), v[0].groupNames)
        assertTrue(v[0].viaFriend)
        assertTrue(v[1].groupNames.isEmpty())

        val p = json.decodeFromString<List<GroupPreview>>(
            """[{"group_id":"g","group_name":"Arosa","owner_name":"Greta","member_count":2,"is_full":false,"already_member":false}]""",
        ).single()
        assertEquals("Arosa", p.groupName)
        assertFalse(p.isFull)

        val pend = json.decodeFromString<List<GroupPendingRow>>("""[{"friend_id":"u5","display_name":"Kai","created_at":null,"expires_at":null}]""").single()
        assertEquals("Kai", pend.displayName)
    }
}
