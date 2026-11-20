package app.pwdr.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwdr.R
import app.pwdr.tracking.Activity
import app.pwdr.tracking.LiveState
import app.pwdr.tracking.TrackingService
import app.pwdr.tracking.TrackingStore

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recording by TrackingStore.recording.collectAsState()
    val live by TrackingStore.live.collectAsState()

    // Bildschirm bleibt während der Aufzeichnung an (Anzeige gut sichtbar, siehe PLAN.md 5a).
    val view = LocalView.current
    DisposableEffect(recording) {
        view.keepScreenOn = recording
        onDispose { view.keepScreenOn = false }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // Ohne Benachrichtigungen läuft die Aufzeichnung trotzdem, die Meldung ist dann unsichtbar.
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            TrackingService.start(context)
        }
    }

    fun startRecording() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        launcher.launch(perms.toTypedArray())
    }

    var showVoice by remember { mutableStateOf(false) }
    if (showVoice) VoiceSettingsDialog(onDismiss = { showVoice = false })
    var showG by remember { mutableStateOf(false) }
    if (showG) GForceSettingsDialog(recording = recording, onDismiss = { showG = false })

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showG = true }, modifier = Modifier.width(96.dp)) { Text("G-Force") }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ActivityChip(recording = recording, activity = live.activity)
            }
            TextButton(onClick = { showVoice = true }, modifier = Modifier.width(96.dp)) {
                Text(stringResource(R.string.voice_button))
            }
        }

        // Hinweis auf eine neue Version, nie während der Aufzeichnung.
        if (!recording) app.pwdr.update.UpdateCard(Modifier.padding(top = 8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (recording) kmh(live.speedMs).toString() else "–",
                fontSize = 120.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.record_unit_kmh),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recording && live.accuracyM > 20f) {
                Text(
                    text = stringResource(R.string.record_gps_weak),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        StatGrid(live, recording)

        Box(Modifier.height(16.dp))

        if (!recording) {
            BigButton(
                text = stringResource(R.string.record_start),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = ::startRecording,
                onLongClick = null,
            )
        } else {
            BigButton(
                text = stringResource(R.string.record_stop),
                container = MaterialTheme.colorScheme.error,
                content = Color.White,
                onClick = {},
                onLongClick = { TrackingService.stop(context) },
            )
            Text(
                text = stringResource(R.string.record_hold_to_stop),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ActivityChip(recording: Boolean, activity: Activity) {
    val (label, color) = when {
        !recording -> stringResource(R.string.record_ready) to MaterialTheme.colorScheme.onSurfaceVariant
        activity == Activity.DOWNHILL -> stringResource(R.string.activity_downhill) to MaterialTheme.colorScheme.primary
        activity == Activity.LIFT -> stringResource(R.string.activity_lift) to MaterialTheme.colorScheme.secondary
        else -> stringResource(R.string.activity_pause) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .height(10.dp)
                .padding(end = 0.dp)
                .background(color, RoundedCornerShape(50))
                .padding(5.dp),
        )
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun StatGrid(live: LiveState, recording: Boolean) {
    val dash = "–"
    fun v(text: String) = if (recording) text else dash
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.label_vmax), v("${kmh(live.maxSpeedMs)} km/h"), Modifier.weight(1f))
            Stat(stringResource(R.string.label_altitude), v(formatMeters(live.altM)), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.label_distance), v(formatDistance(live.totalDistM)), Modifier.weight(1f))
            Stat(stringResource(R.string.label_vert_down), v(formatMeters(live.totalVertDownM)), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.label_runs), v(live.runCount.toString()), Modifier.weight(1f))
            Stat(stringResource(R.string.label_lifts), v(live.liftCount.toString()), Modifier.weight(1f))
            Stat(stringResource(R.string.label_time), v(formatDuration(live.elapsedMs)), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val g = live.gNow?.let { "${formatG(it)} / ${formatG(live.maxG ?: it)} g" }
            Stat("G jetzt / Max", v(g ?: dash), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigButton(
    text: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(container, RoundedCornerShape(36.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
    }
}
