package app.pwdr.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwdr.social.GroupInvitationRow
import app.pwdr.social.GroupMemberRow
import app.pwdr.social.GroupPendingRow
import app.pwdr.social.GroupRow
import app.pwdr.social.GroupTexts
import app.pwdr.social.Social
import kotlinx.coroutines.delay
import java.time.LocalDate

// Tab "Freunde" mit zwei Seiten: Freunde und Gruppen (PLAN.md 5f).

/** Umschalter oben und der Inhalt der gewählten Seite. Alle Dialoge zum Beitritt stehen hier, damit sie auf beiden Seiten erscheinen. */
@Composable
internal fun FriendsAndGroups() {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val invitations by Social.invitations.collectAsState()
    val joinPreview by Social.joinPreview.collectAsState()
    val pendingJoin by Social.pendingJoinCode.collectAsState()
    var inviteToAccept by remember { mutableStateOf<GroupInvitationRow?>(null) }

    // Link pwdr://join/CODE oder pwdr://group/CODE: nachfragen, dann den Code prüfen (Freund oder Gruppe).
    pendingJoin?.let { code ->
        AlertDialog(
            onDismissRequest = { Social.pendingJoinCode.value = null },
            title = { Text("Einladung einlösen?") },
            text = { Text("Der Code $code gehört zu einem Freund oder zu einer Gruppe. Zuerst siehst du, worum es geht.") },
            confirmButton = {
                TextButton(onClick = {
                    Social.handleCode(code)
                    Social.pendingJoinCode.value = null
                }) { Text("Prüfen") }
            },
            dismissButton = { TextButton(onClick = { Social.pendingJoinCode.value = null }) { Text("Abbrechen") } },
        )
    }
    joinPreview?.let { (_, preview) ->
        JoinConfirmDialog(
            groupName = preview.groupName,
            fromText = "von ${preview.ownerName}",
            memberCount = preview.memberCount,
            full = preview.isFull && !preview.alreadyMember,
            onJoin = {
                Social.confirmJoinGroup()
                page = 1
            },
            onCancel = Social::cancelJoinPreview,
        )
    }
    inviteToAccept?.let { inv ->
        JoinConfirmDialog(
            groupName = inv.groupName,
            fromText = "Einladung von ${inv.inviterName}",
            memberCount = inv.memberCount,
            full = false,
            onJoin = {
                Social.acceptGroupInvitation(inv.groupId)
                inviteToAccept = null
                openGroup = null
            },
            onCancel = { inviteToAccept = null },
        )
    }

    Column {
        PageSwitch(page, invitations.size) {
            page = it
            openGroup = null
        }
        when {
            page == 0 -> FriendsContent()
            openGroup != null -> GroupDetail(openGroup.orEmpty(), onBack = { openGroup = null })
            else -> GroupsList(onOpen = { openGroup = it }, onAccept = { inviteToAccept = it })
        }
    }
}

@Composable
private fun PageSwitch(page: Int, invitationCount: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("Freunde", if (invitationCount > 0) "Gruppen ($invitationCount neu)" else "Gruppen").forEachIndexed { i, label ->
            val on = i == page
            Text(
                text = label,
                modifier = Modifier
                    .weight(1f)
                    .background(if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(9.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ------------------------------------------------------------------ Beitritt bestätigen

@Composable
private fun JoinConfirmDialog(
    groupName: String,
    fromText: String,
    memberCount: Int,
    full: Boolean,
    onJoin: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Gruppe beitreten?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\"$groupName\"", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("$fromText, ${GroupTexts.memberCount(memberCount)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (full) {
                    Text("Die Gruppe ist voll (höchstens ${GroupTexts.MAX_MEMBERS} Mitglieder).", color = MaterialTheme.colorScheme.error)
                } else {
                    Text(GroupTexts.JOIN_NOTICE)
                }
            }
        },
        confirmButton = { TextButton(onClick = onJoin, enabled = !full) { Text("Beitreten") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } },
    )
}

// ------------------------------------------------------------------ Liste

@Composable
private fun GroupsList(onOpen: (String) -> Unit, onAccept: (GroupInvitationRow) -> Unit) {
    val groups by Social.groups.collectAsState()
    val invitations by Social.invitations.collectAsState()
    val diag by Social.diag.collectAsState()
    val busy by Social.busy.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) { Social.refreshGroupsNow() }

    if (showCreate) CreateGroupDialog(onDismiss = { showCreate = false })

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        diag.groupsNote?.let { note ->
            item {
                GroupCard {
                    Text("Gruppen sind noch nicht verfügbar", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Status: $note. Im Supabase-Projekt muss die Datei 0006_groups.sql ausgeführt werden (siehe backend/README.md).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (invitations.isNotEmpty()) {
            item { Text("Einladungen", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground) }
            items(invitations, key = { "i-" + it.groupId }) { inv ->
                GroupCard {
                    Text(inv.groupName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Einladung von ${inv.inviterName}, ${GroupTexts.memberCount(inv.memberCount)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAccept(inv) }, enabled = !busy) { Text("Ansehen") }
                        TextButton(onClick = { Social.declineGroupInvitation(inv.groupId) }, enabled = !busy) { Text("Ablehnen") }
                    }
                }
            }
        }

        item { Text("Meine Gruppen", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground) }
        if (groups.isEmpty()) {
            item {
                Text(
                    "Noch keine Gruppe. In einer Gruppe sehen sich alle Mitglieder auf der Karte, solange sie aufzeichnen, auch ohne befreundet zu sein.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(groups, key = { it.groupId }) { g ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                    .clickable { onOpen(g.groupId) }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (g.isOwner) Text("Ersteller", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "${GroupTexts.memberCount(g.memberCount)} · ${GroupTexts.expiry(g, now)}" +
                        (if (g.isOwner && g.pendingCount > 0) " · ${g.pendingCount} eingeladen" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (g.isActive(now)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                if (!g.shareLocation) {
                    Text("Du teilst deinen Standort hier nicht", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            GroupCard {
                Button(onClick = { showCreate = true }, enabled = !busy) { Text("Gruppe erstellen") }
                Text("Code erhalten?", color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { if (it.length <= 6) code = it.uppercase() },
                        label = { Text("Code") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { Social.handleCode(code); code = "" }, enabled = !busy && code.length == 6) { Text("Prüfen") }
                }
                Text(
                    "Der Code gehört zu einer Gruppe oder zu einem Freund. Bei einer Gruppe siehst du zuerst, worum es geht.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

// ------------------------------------------------------------------ Erstellen

@Composable
private fun CreateGroupDialog(onDismiss: () -> Unit) {
    val friends by Social.friends.collectAsState()
    val accepted = remember(friends) { friends.filter { it.status == "accepted" } }
    val options = remember { GroupTexts.durationOptions(LocalDate.now()) }
    var name by remember { mutableStateOf("") }
    var durationIdx by remember { mutableIntStateOf(GroupTexts.DEFAULT_DURATION_INDEX) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gruppe erstellen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= GroupTexts.MAX_NAME) name = it },
                    label = { Text("Name, zum Beispiel Wochenende Arosa") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Wie lange soll die Gruppe laufen?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                options.forEachIndexed { i, o ->
                    Row(Modifier.fillMaxWidth().clickable { durationIdx = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = durationIdx == i, onClick = { durationIdx = i })
                        Text(o.label)
                    }
                }
                Text(
                    "Danach endet die Sicht auf die Standorte sofort. Verlängern ist jederzeit möglich.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (accepted.isNotEmpty()) {
                    Text("Freunde einladen (sie müssen annehmen)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    accepted.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = if (f.friendId in selected) selected - f.friendId else selected + f.friendId },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = f.friendId in selected, onCheckedChange = { selected = if (it) selected + f.friendId else selected - f.friendId })
                            Box(Modifier.size(12.dp).background(parseColor(f.color), CircleShape))
                            Spacer(Modifier.size(8.dp))
                            Text(f.displayName)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    Social.createGroup(name.trim(), options[durationIdx].days, selected.toList())
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) { Text("Erstellen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ------------------------------------------------------------------ Gruppenseite

@Composable
private fun GroupDetail(groupId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val groups by Social.groups.collectAsState()
    val allMembers by Social.groupMembers.collectAsState()
    val friends by Social.friends.collectAsState()
    val inviteCode by Social.groupInviteCode.collectAsState()
    val profile by Social.profile.collectAsState()
    val busy by Social.busy.collectAsState()
    val group = groups.firstOrNull { it.groupId == groupId }
    val members = allMembers[groupId].orEmpty()
    val myId = profile?.id
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    var pending by remember { mutableStateOf<List<GroupPendingRow>>(emptyList()) }
    var showRename by remember { mutableStateOf(false) }
    var showExtend by remember { mutableStateOf(false) }
    var showAddFriends by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<GroupMemberRow?>(null) }

    BackHandler(onBack = onBack)
    // Gruppe gelöscht, verlassen oder aus der Liste verschwunden: zurück zur Liste.
    LaunchedEffect(group == null) { if (group == null) onBack() }
    LaunchedEffect(groupId) { Social.refreshGroupsNow() }
    // Offene Einladungen (nur Ersteller) nachladen, sobald sich ihre Zahl ändert.
    LaunchedEffect(groupId, group?.pendingCount, group?.isOwner) {
        pending = if (group?.isOwner == true && group.pendingCount > 0) Social.loadPending(groupId) else emptyList()
    }
    if (group == null) return

    if (showRename) RenameDialog(group.name, onDismiss = { showRename = false }) { Social.renameGroup(groupId, it) }
    if (showExtend) ExtendDialog(onDismiss = { showExtend = false }) { Social.extendGroup(groupId, it) }
    if (showAddFriends) {
        val taken = members.map { it.memberId }.toSet() + pending.map { it.friendId }.toSet()
        AddFriendsDialog(
            candidates = friends.filter { it.status == "accepted" && it.friendId !in taken },
            onDismiss = { showAddFriends = false },
        ) { Social.inviteFriendsToGroup(groupId, it) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Gruppe löschen?") },
            text = { Text("\"${group.name}\" wird für alle Mitglieder gelöscht. Niemand sieht danach mehr die Standorte der anderen über diese Gruppe.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; Social.deleteGroup(groupId) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Gruppe verlassen?") },
            text = {
                Text(
                    if (group.isOwner) {
                        "Du bist Ersteller. Die Gruppe geht an das am längsten beigetretene Mitglied. Gibt es kein anderes Mitglied, wird sie gelöscht."
                    } else {
                        "Du siehst die anderen nicht mehr und sie sehen dich nicht mehr. Du kannst nur mit einem neuen Code oder einer Einladung zurück."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { confirmLeave = false; Social.leaveGroup(groupId) }) { Text("Verlassen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Abbrechen") } },
        )
    }
    confirmRemove?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("${m.displayName} entfernen?") },
            text = { Text("Die Person sieht niemanden mehr und kommt nicht mit einem Code zurück. Du kannst sie später als Freund neu einladen.") },
            confirmButton = { TextButton(onClick = { confirmRemove = null; Social.removeMember(groupId, m.memberId) }) { Text("Entfernen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Abbrechen") } },
        )
    }
    inviteCode?.takeIf { it.first == groupId }?.let { (_, code) ->
        AlertDialog(
            onDismissRequest = Social::clearGroupInviteCode,
            title = { Text("Einladungscode") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(code, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "24 Stunden gültig, höchstens $INVITE_MAX_USES Beitritte. Wer ihn bekommt, sieht vor dem Beitritt, worum es geht, und muss bestätigen. " +
                            "Schick ihn nur an Personen, die alle Mitglieder sehen dürfen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, GroupTexts.inviteShareText(code, group.name, INVITE_MAX_USES))
                    }
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Teilen") }
            },
            dismissButton = { TextButton(onClick = Social::clearGroupInviteCode) { Text("Fertig") } },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Zurück") }
                Spacer(Modifier.weight(1f))
                if (group.isOwner) TextButton(onClick = { showRename = true }) { Text("Umbenennen") }
            }
            Text(group.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "${GroupTexts.memberCount(group.memberCount)} · ${GroupTexts.expiry(group, now)} · Ersteller: ${group.ownerName}",
                style = MaterialTheme.typography.bodySmall,
                color = if (group.isActive(now)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            if (!group.isActive(now)) {
                Text(
                    if (group.isOwner) "Die Gruppe ist abgelaufen, niemand sieht jemanden. Verlängere sie oder lösche sie."
                    else "Die Gruppe ist abgelaufen. Der Ersteller kann sie verlängern.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Meine Einstellungen für diese Gruppe
        item {
            GroupCard {
                Text("Meine Einstellungen", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Meinen Standort in dieser Gruppe teilen", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = group.shareLocation, onCheckedChange = { Social.setGroupSharing(groupId, it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ansage, wenn jemand in der Nähe ist", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = group.announce, onCheckedChange = { Social.setGroupAnnounce(groupId, it) })
                }
                Text(
                    if (group.shareLocation) "Die Mitglieder sehen dich, solange du aufzeichnest. Du siehst alle, die aufzeichnen."
                    else "Die Mitglieder sehen dich nicht. Du siehst weiterhin alle, die aufzeichnen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Mitglieder
        item { Text("Mitglieder", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground) }
        items(members, key = { "m-" + it.memberId }) { m ->
            val isMe = m.memberId == myId
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(14.dp).background(parseColor(m.color), CircleShape))
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(m.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        if (m.role == "owner") Text("Ersteller", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        if (GroupTexts.isNew(m.joinedAt, now)) Text("neu", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(GroupTexts.memberStatus(m, isMe), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (group.isOwner && !isMe) TextButton(onClick = { confirmRemove = m }) { Text("Entfernen", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (group.isOwner && pending.isNotEmpty()) {
            item { Text("Eingeladen (noch nicht angenommen)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground) }
            items(pending, key = { "p-" + it.friendId }) { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.displayName, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { Social.cancelGroupInvitation(groupId, p.friendId) }) { Text("Zurückziehen") }
                }
            }
        }

        // Einladen (nur Ersteller)
        if (group.isOwner) {
            item {
                GroupCard {
                    Text("Einladen", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Freunde müssen die Einladung annehmen, erst dann sehen sie die Gruppe und werden gesehen. " +
                            "Höchstens ${GroupTexts.MAX_MEMBERS} Mitglieder, offene Einladungen eingerechnet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showAddFriends = true }, enabled = !busy && group.isActive(now)) { Text("Freunde hinzufügen") }
                        Button(onClick = { Social.createGroupInvite(groupId, INVITE_MAX_USES) }, enabled = !busy && group.isActive(now)) { Text("Per Code") }
                    }
                    TextButton(onClick = { Social.revokeGroupInvites(groupId) }, enabled = !busy) { Text("Alle offenen Codes widerrufen") }
                }
            }
            item {
                GroupCard {
                    Text("Laufzeit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(GroupTexts.expiry(group, now), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showExtend = true }) { Text("Verlängern") }
                }
            }
        }

        item {
            Row {
                TextButton(onClick = { confirmLeave = true }) { Text("Gruppe verlassen", color = MaterialTheme.colorScheme.error) }
                if (group.isOwner) TextButton(onClick = { confirmDelete = true }) { Text("Gruppe löschen", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private const val INVITE_MAX_USES = 10

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gruppe umbenennen") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= GroupTexts.MAX_NAME) name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()); onDismiss() }, enabled = name.isNotBlank()) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun ExtendDialog(onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    val options = remember { GroupTexts.durationOptions(LocalDate.now()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gruppe verlängern") },
        text = {
            Column {
                Text("Die Laufzeit wird ab dem späteren von Ablauf und heute verlängert.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                options.forEach { o ->
                    TextButton(onClick = { onPick(o.days); onDismiss() }) { Text(if (o.days == null) o.label else "um ${o.label}") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun AddFriendsDialog(candidates: List<app.pwdr.social.Friend>, onDismiss: () -> Unit, onInvite: (List<String>) -> Unit) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Freunde hinzufügen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    Text("Alle deine Freunde sind schon Mitglied oder eingeladen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Sie müssen die Einladung annehmen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                candidates.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (f.friendId in selected) selected - f.friendId else selected + f.friendId },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = f.friendId in selected, onCheckedChange = { selected = if (it) selected + f.friendId else selected - f.friendId })
                        Box(Modifier.size(12.dp).background(parseColor(f.color), CircleShape))
                        Spacer(Modifier.size(8.dp))
                        Text(f.displayName)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onInvite(selected.toList()); onDismiss() }, enabled = selected.isNotEmpty()) { Text("Einladen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
