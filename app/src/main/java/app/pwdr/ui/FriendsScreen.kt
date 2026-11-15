package app.pwdr.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.pwdr.social.AuthState
import app.pwdr.social.Freshness
import app.pwdr.social.Friend
import app.pwdr.social.FriendFreshness
import app.pwdr.social.LiveRow
import app.pwdr.social.Social
import app.pwdr.social.parseInstantMs
import app.pwdr.tracking.TrackingStore
import app.pwdr.voice.VoiceSettingsStore
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val COLORS = listOf("#4CC9F0", "#2EC4B6", "#FFD23F", "#FF5A5F", "#B388FF", "#FF9F43")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

fun parseColor(hex: String, fallback: Color = Color(0xFF4CC9F0)): Color =
    try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: IllegalArgumentException) {
        fallback
    }

/** Tab "Freunde": Anmeldung, Profil, Freunde einladen, Standort teilen (PLAN.md 5c). */
@Composable
fun FriendsScreen(modifier: Modifier = Modifier) {
    val auth by Social.authState.collectAsState()
    val profile by Social.profile.collectAsState()
    val profileLoaded by Social.profileLoaded.collectAsState()
    val message by Social.message.collectAsState()

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        if (message != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = Social::clearMessage) { Text("OK") }
            }
        }
        when {
            auth == AuthState.LOADING -> Centered { CircularProgressIndicator() }
            auth == AuthState.SIGNED_OUT -> LoginCard()
            !profileLoaded -> Centered { CircularProgressIndicator() }
            profile == null -> ProfileSetup()
            else -> FriendsContent()
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

// ------------------------------------------------------------------ Anmeldung

@Composable
private fun LoginCard() {
    val sentTo by Social.linkSentTo.collectAsState()
    val busy by Social.busy.collectAsState()
    var email by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Freunde", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Card {
            if (sentTo == null) {
                Text(
                    "Melde dich mit deiner E-Mail-Adresse an. Du bekommst einen Link, ein Passwort brauchst du nicht.",
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("E-Mail") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { Social.sendMagicLink(email) }, enabled = !busy && "@" in email) {
                    Text("Link senden")
                }
            } else {
                Text("Link gesendet an $sentTo", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Öffne die E-Mail auf diesem Handy und tippe auf den Link. Du kommst direkt zurück in die App. " +
                        "Schau auch im Spam-Ordner nach.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = Social::resetLinkSent) { Text("Andere Adresse verwenden") }
            }
        }
        Text(
            "Deine Fahrten bleiben auf dem Handy. Nur wenn du aktiv teilst, sehen ausgewählte Freunde deine Position.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileSetup() {
    val busy by Social.busy.collectAsState()
    var name by remember { mutableStateOf("") }
    var spoken by remember { mutableStateOf("") }
    var colorIdx by remember { mutableIntStateOf(0) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dein Profil", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Card {
            Text("So sehen dich deine Freunde, und so wird dein Name angesagt.", color = MaterialTheme.colorScheme.onSurface)
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 30) name = it },
                label = { Text("Anzeigename") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = spoken,
                onValueChange = { if (it.length <= 30) spoken = it },
                label = { Text("Aussprache (optional)") },
                supportingText = { Text("Falls die Stimme deinen Namen falsch liest, schreib ihn hier, wie er klingt.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                COLORS.forEachIndexed { i, hex ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(parseColor(hex), CircleShape)
                            .clickable { colorIdx = i }
                            .then(
                                if (i == colorIdx) Modifier.padding(3.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(3.dp).background(parseColor(hex), CircleShape)
                                else Modifier,
                            ),
                    )
                }
            }
            Button(onClick = { Social.saveProfile(name, COLORS[colorIdx], spoken) }, enabled = !busy && name.isNotBlank()) {
                Text("Speichern")
            }
        }
        TextButton(onClick = Social::signOut) { Text("Abmelden") }
    }
}

// ------------------------------------------------------------------ Inhalt

@Composable
private fun FriendsContent() {
    val context = LocalContext.current
    val profile by Social.profile.collectAsState()
    val friends by Social.friends.collectAsState()
    val share by Social.share.collectAsState()
    val live by Social.live.collectAsState()
    val inviteCode by Social.inviteCode.collectAsState()
    val pendingJoin by Social.pendingJoinCode.collectAsState()
    val recording by TrackingStore.recording.collectAsState()
    val busy by Social.busy.collectAsState()
    var hours by remember { mutableIntStateOf(8) }
    var code by remember { mutableStateOf("") }
    val voiceStore = remember { VoiceSettingsStore(context) }
    var muted by remember { mutableStateOf(voiceStore.load().mutedFriendIds) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf<Friend?>(null) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(10_000)
            value = System.currentTimeMillis()
        }
    }

    val accepted = friends.filter { it.status == "accepted" }
    val blocked = friends.filter { it.status == "blocked" }

    if (pendingJoin != null) {
        AlertDialog(
            onDismissRequest = { Social.pendingJoinCode.value = null },
            title = { Text("Einladung einlösen?") },
            text = { Text("Mit dem Code $pendingJoin Freundschaft schliessen.") },
            confirmButton = {
                TextButton(onClick = {
                    Social.redeemInvite(pendingJoin.orEmpty())
                    Social.pendingJoinCode.value = null
                }) { Text("Einlösen") }
            },
            dismissButton = { TextButton(onClick = { Social.pendingJoinCode.value = null }) { Text("Abbrechen") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Konto löschen?") },
            text = { Text("Profil, Freundschaften und Freigaben werden gelöscht. Deine Fahrten auf dem Handy bleiben.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; Social.deleteAccount() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
    confirmBlock?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmBlock = null },
            title = { Text("${f.displayName} blockieren?") },
            text = { Text("Die Freundschaft endet, alle Freigaben werden sofort beendet. Eine neue Einladung funktioniert dann nicht.") },
            confirmButton = { TextButton(onClick = { confirmBlock = null; Social.blockFriend(f.friendId) }) { Text("Blockieren") } },
            dismissButton = { TextButton(onClick = { confirmBlock = null }) { Text("Abbrechen") } },
        )
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(parseColor(profile?.color.orEmpty()), CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(profile?.displayName.orEmpty(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = Social::signOut) { Text("Abmelden") }
            }
        }

        // Teilen
        item {
            Card {
                if (share.active) {
                    val n = share.friendIds.size
                    Text(
                        "Du teilst deinen Standort mit $n ${if (n == 1) "Person" else "Personen"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Bis ${Instant.ofEpochMilli(share.expiresAtMs).atZone(ZoneId.systemDefault()).format(CLOCK)} Uhr. " +
                            if (recording) "Deine Position wird gesendet." else "Gesendet wird, sobald die Aufzeichnung läuft.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = Social::stopShare, enabled = !busy) { Text("Teilen beenden") }
                } else {
                    Text("Standort teilen", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Teilen ist aus. Wenn du es startest, sehen deine Freunde deine Position, solange die Aufzeichnung läuft " +
                            "und bis die Zeit abläuft.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 4, 8).forEach { h ->
                            Chip("$h h", h == hours) { hours = h }
                        }
                    }
                    Button(
                        onClick = { Social.startShare(hours, accepted.map { it.friendId }) },
                        enabled = !busy && accepted.isNotEmpty(),
                    ) { Text("Mit allen Freunden teilen") }
                    if (accepted.isEmpty()) {
                        Text("Lade zuerst einen Freund ein.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Freunde
        item { Text("Freunde", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground) }
        if (accepted.isEmpty()) {
            item { Text("Noch keine Freunde.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(accepted, key = { it.friendId }) { f ->
            FriendRow(
                friend = f,
                live = live[f.friendId],
                nowMs = now,
                shareActive = share.active,
                sharedWithThem = f.friendId in share.friendIds,
                announceOn = f.friendId !in muted,
                onToggleAnnounce = { on ->
                    val next = if (on) muted - f.friendId else muted + f.friendId
                    muted = next
                    voiceStore.save(voiceStore.load().copy(mutedFriendIds = next))
                },
                onToggleShare = { Social.setShareTarget(f.friendId, it) },
                onRemove = { Social.removeFriend(f.friendId) },
                onBlock = { confirmBlock = f },
            )
        }
        if (blocked.isNotEmpty()) {
            item { Text("Blockiert", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground) }
            items(blocked, key = { "b-" + it.friendId }) { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.displayName, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { Social.unblockFriend(f.friendId) }) { Text("Aufheben") }
                }
            }
        }

        // Einladen
        item {
            Card {
                Text("Freund einladen", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (inviteCode == null) {
                    Text(
                        "Du bekommst einen Code, 24 Stunden gültig und einmal einlösbar. Schick ihn deinem Freund.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = Social::createInvite, enabled = !busy) { Text("Code erzeugen") }
                } else {
                    Text(
                        inviteCode.orEmpty(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Komm zu PWDR! Code: $inviteCode (24 Stunden gültig) oder Link: pwdr://join/$inviteCode",
                                )
                            }
                            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Teilen") }
                        TextButton(onClick = Social::clearInviteCode) { Text("Fertig") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Code erhalten?", color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { if (it.length <= 6) code = it.uppercase() },
                        label = { Text("Code") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { Social.redeemInvite(code); code = "" }, enabled = !busy && code.length == 6) {
                        Text("Einlösen")
                    }
                }
            }
        }

        item {
            TextButton(onClick = { confirmDelete = true }) {
                Text("Konto löschen", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun FriendRow(
    friend: Friend,
    live: LiveRow?,
    nowMs: Long,
    shareActive: Boolean,
    sharedWithThem: Boolean,
    announceOn: Boolean,
    onToggleAnnounce: (Boolean) -> Unit,
    onToggleShare: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onBlock: () -> Unit,
) {
    val status = liveStatusText(live, nowMs)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).background(parseColor(friend.color), CircleShape))
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(friend.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (shareActive) {
                Switch(checked = sharedWithThem, onCheckedChange = onToggleShare)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ansage in der Nähe", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = announceOn, onCheckedChange = onToggleAnnounce)
        }
        Row {
            TextButton(onClick = onRemove) { Text("Entfernen") }
            TextButton(onClick = onBlock) { Text("Blockieren", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Anzeige des Stands eines Freundes: aktuell, "zuletzt vor 2 min" oder nicht sichtbar. */
fun liveStatusText(live: LiveRow?, nowMs: Long): String {
    if (live == null) return "Teilt gerade nicht"
    val updated = try {
        parseInstantMs(live.updatedAt)
    } catch (e: Exception) {
        return "Teilt gerade nicht"
    }
    return when (FriendFreshness.of(updated, nowMs)) {
        Freshness.HIDDEN -> "Teilt gerade nicht"
        Freshness.STALE -> "zuletzt ${FriendFreshness.ageLabel(updated, nowMs)}"
        Freshness.FRESH -> buildString {
            append(FriendFreshness.ageLabel(updated, nowMs))
            live.speed?.let { if (live.state == "downhill") append(" · ${kmh(it)} km/h") }
            when (live.state) {
                "downhill" -> append(" · Abfahrt")
                "lift" -> append(" · Lift")
                "pause" -> append(" · Pause")
            }
        }
    }
}
