package app.pwdr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwdr.social.ApprovalLogic
import app.pwdr.social.PendingAccount
import app.pwdr.social.Social

/** Seite für ein Konto, das noch nicht freigegeben ist (0007). Aufzeichnen, Karte und Statistik gehen weiter. */
@Composable
internal fun PendingApprovalCard() {
    val busy by Social.busy.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Freunde", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                ApprovalLogic.PENDING_TITLE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(ApprovalLogic.PENDING_TEXT, color = MaterialTheme.colorScheme.onSurface)
            Button(onClick = Social::refresh, enabled = !busy) { Text("Neu prüfen") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = Social::signOut) { Text("Abmelden") }
                TextButton(onClick = { confirmDelete = true }) { Text("Konto löschen") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Konto löschen?") },
            text = { Text("Dein Konto und alle Daten dazu werden gelöscht. Das lässt sich nicht rückgängig machen.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; Social.deleteAccount() }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

/** Nur für Admins: Hinweis auf wartende Konten, öffnet die Liste zum Freigeben oder Ablehnen. */
@Composable
internal fun AdminAccountsRow() {
    val approval by Social.approval.collectAsState()
    var open by remember { mutableStateOf(false) }
    if (!approval.isAdmin || approval.pendingCount <= 0) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ApprovalLogic.waitingText(approval.pendingCount),
            Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = { open = true }) { Text("Ansehen") }
    }
    if (open) AdminAccountsDialog(onDismiss = { open = false })
}

@Composable
private fun AdminAccountsDialog(onDismiss: () -> Unit) {
    val pending by Social.pendingAccounts.collectAsState()
    val busy by Social.busy.collectAsState()
    var reject by remember { mutableStateOf<PendingAccount?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Freigaben") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pending.isEmpty()) Text("Keine wartenden Konten.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                pending.forEach { a ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(a.displayName?.takeIf { it.isNotBlank() } ?: "(kein Name)", fontWeight = FontWeight.SemiBold)
                        Text(a.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { Social.approveAccount(a.userId) }, enabled = !busy) { Text("Freigeben") }
                            OutlinedButton(onClick = { reject = a }, enabled = !busy) { Text("Ablehnen") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schliessen") } },
    )
    reject?.let { a ->
        AlertDialog(
            onDismissRequest = { reject = null },
            title = { Text("Konto ablehnen?") },
            text = { Text("${a.email} wird gelöscht. Die Person kann sich später neu anmelden.") },
            confirmButton = {
                TextButton(onClick = { Social.rejectAccount(a.userId); reject = null }) { Text("Ablehnen") }
            },
            dismissButton = { TextButton(onClick = { reject = null }) { Text("Abbrechen") } },
        )
    }
}
