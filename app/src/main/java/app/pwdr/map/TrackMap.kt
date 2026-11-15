package app.pwdr.map

import android.graphics.Color as AColor
import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.core.view.doOnLayout
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.pwdr.R
import app.pwdr.data.PwdrDatabase
import app.pwdr.tracking.TrackBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

/** Farbskala der Geschwindigkeit (km/h): dunkelviolett, türkis, gelb. Hebt sich von den Pistenfarben ab. */
private val SPEED_STOPS = listOf(0f to "#440154", 25f to "#21908C", 50f to "#FDE725")
private const val LIFT_COLOR = "#8A9AA6"
private const val SPEED_MAX_KMH = 50

/** Violett wie die Metrik G-Force. */
private const val PEAK_COLOR = "#9B5DE5"

/**
 * Zeigt eine gefahrene Spur auf der Karte, Abfahrten nach Speed gefärbt, Lifte grau.
 * [trackJson] ist null, solange die Daten laden.
 */
@Composable
fun TrackMapView(
    trackJson: String?,
    bounds: TrackBounds?,
    modifier: Modifier = Modifier,
    peak: app.pwdr.tracking.GForceStats.Peak? = null,
) {
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            mapView.apply {
                onCreate(null)
                getMapAsync { m ->
                    m.uiSettings.isAttributionEnabled = false
                    m.uiSettings.isLogoEnabled = false
                    m.setStyle(Style.Builder().fromJson(SwisstopoStyle.json)) { s -> style = s }
                    map = m
                }
            }
        },
    )

    OsmLayersEffect(style)

    // Stärkste Kurve (höchster G-Wert) als violetter Punkt
    LaunchedEffect(style, peak) {
        val st = style ?: return@LaunchedEffect
        val empty = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        val json = if (peak == null) empty else String.format(
            java.util.Locale.ROOT,
            "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":[%.6f,%.6f]}}]}",
            peak.lon, peak.lat,
        )
        val data = FeatureCollection.fromJson(json)
        val existing = st.getSourceAs<GeoJsonSource>("gpeak")
        if (existing != null) {
            existing.setGeoJson(data)
            return@LaunchedEffect
        }
        st.addSource(GeoJsonSource("gpeak", data))
        st.addLayer(
            org.maplibre.android.style.layers.CircleLayer("gpeak-dot", "gpeak").withProperties(
                PropertyFactory.circleColor(AColor.parseColor(PEAK_COLOR)),
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleStrokeColor(AColor.WHITE),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }

    LaunchedEffect(style, trackJson, bounds) {
        val st = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val json = trackJson ?: return@LaunchedEffect
        setTrack(st, json)
        if (bounds != null) {
            val box = LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west)
            // Erst nach dem Layout, sonst kennt die Karte ihre Grösse nicht. Oben und unten bleibt Platz
            // für Kopfzeile und Legende.
            mapView.doOnLayout {
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(box, 60, 340, 60, 320))
            }
        }
    }
}

private fun setTrack(style: Style, json: String) {
    val data = FeatureCollection.fromJson(json)
    val existing = style.getSourceAs<GeoJsonSource>("track")
    if (existing != null) {
        existing.setGeoJson(data)
        return
    }
    style.addSource(GeoJsonSource("track", data))
    style.addLayer(
        LineLayer(TRACK_CASING_LAYER, "track").withProperties(
            PropertyFactory.lineColor(AColor.WHITE),
            PropertyFactory.lineWidth(8f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    val speedColor = Expression.interpolate(
        Expression.linear(),
        Expression.get("speed"),
        *SPEED_STOPS.map { (v, c) -> Expression.stop(v, Expression.color(AColor.parseColor(c))) }.toTypedArray(),
    )
    val color = Expression.switchCase(
        Expression.eq(Expression.get("activity"), Expression.literal("LIFT")),
        Expression.color(AColor.parseColor(LIFT_COLOR)),
        speedColor,
    )
    style.addLayer(
        LineLayer("track", "track").withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
}

/** Legende der Spurfarben: Farbverlauf für Speed, grau für Lifte. */
@Composable
fun TrackLegend(modifier: Modifier = Modifier, peakText: String? = null) {
    val gradient = Brush.horizontalGradient(
        SPEED_STOPS.map { (_, c) -> Color(AColor.parseColor(c)) },
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(160.dp).height(8.dp).background(gradient, RoundedCornerShape(4.dp)))
                Text("0 – $SPEED_MAX_KMH+ km/h", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(160.dp).height(4.dp).background(Color(AColor.parseColor(LIFT_COLOR)), RoundedCornerShape(2.dp)))
                Text(stringResource(R.string.legend_lift), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            if (peakText != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(160.dp), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.width(14.dp).height(14.dp).background(Color(AColor.parseColor(PEAK_COLOR)), androidx.compose.foundation.shape.CircleShape))
                    }
                    Text(peakText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
