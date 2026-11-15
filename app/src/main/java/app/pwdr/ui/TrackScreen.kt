package app.pwdr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwdr.R
import app.pwdr.data.PwdrDatabase
import app.pwdr.data.toSample
import app.pwdr.data.RunEntity
import app.pwdr.map.TrackLegend
import app.pwdr.map.TrackMapView
import app.pwdr.tracking.Activity
import app.pwdr.tracking.ActivityRange
import app.pwdr.tracking.GForceStats
import app.pwdr.tracking.Sample
import app.pwdr.tracking.TrackBounds
import app.pwdr.tracking.TrackGeoJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vollbild-Karte mit der gefahrenen Spur. [runId] zeigt nur diese Abfahrt (bzw. diesen Lift),
 * null zeigt den ganzen Tag.
 */
@Composable
fun TrackScreen(
    title: String,
    runs: List<RunEntity>,
    runId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var json by remember { mutableStateOf<String?>(null) }
    var bounds by remember { mutableStateOf<TrackBounds?>(null) }
    var peak by remember { mutableStateOf<GForceStats.Peak?>(null) }

    LaunchedEffect(runs, runId) {
        val (j, b, pk) = withContext(Dispatchers.IO) {
            val db = PwdrDatabase.get(context)
            val selected = if (runId == null) runs else runs.filter { it.id == runId }
            val from = selected.minOfOrNull { it.startMs } ?: 0L
            val to = selected.maxOfOrNull { it.endMs } ?: 0L
            val sessions = selected.map { it.sessionId }.distinct().sorted().map { id ->
                db.points().forSession(id)
                    .filter { it.timeMs in from..to }
                    .map { it.toSample() }
            }
            val ranges = selected.map { ActivityRange(Activity.valueOf(it.type), it.startMs, it.endMs) }
            Triple(TrackGeoJson.build(sessions, ranges), TrackGeoJson.bounds(sessions), GForceStats.peakIn(sessions, ranges))
        }
        json = j
        bounds = b
        peak = pk
    }

    Box(modifier.fillMaxSize()) {
        TrackMapView(trackJson = json, bounds = bounds, peak = peak)

        Surface(
            modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Text(
                    stringResource(R.string.map_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                )
            }
        }

        if (json != null && bounds == null) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    stringResource(R.string.stats_no_profile),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        TrackLegend(
            Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(12.dp),
            peakText = peak?.let { "Stärkste Kurve ${formatG(it.g)} g" },
        )
    }
}
