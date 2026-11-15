package app.pwdr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pwdr.R
import app.pwdr.voice.Announcement
import app.pwdr.voice.Kind
import app.pwdr.voice.Priority
import app.pwdr.voice.SpeechText
import app.pwdr.voice.VoiceOutput
import app.pwdr.voice.VoiceSettings
import app.pwdr.voice.VoiceSettingsStore

/** Einstellungen der Sprachausgabe (PLAN.md 5b). Änderungen gelten sofort und werden gespeichert. */
@Composable
fun VoiceSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { VoiceSettingsStore(context) }
    var settings by remember { mutableStateOf(store.load()) }
    fun update(s: VoiceSettings) {
        settings = s
        store.save(s)
    }

    // Eigene Stimme nur für die Test-Taste, solange der Dialog offen ist.
    val tester = remember { VoiceOutput(context) { VoiceSettings() } }
    DisposableEffect(Unit) { onDispose { tester.shutdown() } }
    val germanAvailable by VoiceOutput.germanVoiceAvailable.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_done)) } },
        title = { Text(stringResource(R.string.voice_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SwitchRow(stringResource(R.string.voice_enabled), settings.enabled) { update(settings.copy(enabled = it)) }
                SwitchRow(stringResource(R.string.voice_headphones_only), settings.headphonesOnly) {
                    update(settings.copy(headphonesOnly = it))
                }
                Text(
                    stringResource(R.string.voice_speed_interval),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VoiceSettings.INTERVALS_SEC.forEach { sec ->
                        val on = settings.speedIntervalSec == sec
                        Text(
                            text = if (sec == 0) stringResource(R.string.voice_off) else "$sec s",
                            modifier = Modifier
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { update(settings.copy(speedIntervalSec = sec)) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            softWrap = false,
                        )
                    }
                }
                SwitchRow(stringResource(R.string.voice_record), settings.recordEnabled) { update(settings.copy(recordEnabled = it)) }
                SwitchRow(stringResource(R.string.voice_run_end), settings.runEndEnabled) { update(settings.copy(runEndEnabled = it)) }
                SwitchRow(stringResource(R.string.voice_friends), settings.friendsEnabled) { update(settings.copy(friendsEnabled = it)) }
                SwitchRow(stringResource(R.string.voice_button_enabled), settings.buttonEnabled) {
                    update(settings.copy(buttonEnabled = it))
                }
                TextButton(
                    onClick = {
                        tester.offer(
                            Announcement(Kind.TEST, Priority.HIGH, 5_000) {
                                "Hier ist Powder. ${SpeechText.speed(54)}"
                            },
                            force = true,
                        )
                    },
                ) { Text(stringResource(R.string.voice_test)) }
                if (germanAvailable == false) {
                    Text(
                        stringResource(R.string.voice_no_german),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.voice_button_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
