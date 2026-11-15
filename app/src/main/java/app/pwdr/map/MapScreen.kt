package app.pwdr.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pwdr.R
import app.pwdr.ui.ResortScreen
import androidx.compose.foundation.clickable
import app.pwdr.social.FriendsOnMap
import app.pwdr.social.Social
import app.pwdr.ui.parseColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import org.maplibre.android.style.layers.CircleLayer
import app.pwdr.data.OsmRepository
import app.pwdr.data.PwdrDatabase
import app.pwdr.osm.OsmGeoJson
import app.pwdr.osm.WayKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

internal const val TRACK_CASING_LAYER = "track-casing"

// Mitte der Schweiz, als Startansicht ohne Standort.
private val SWITZERLAND = LatLng(46.8, 8.2)

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun MapScreen() {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    var permissionAsked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionAsked = true
        permissionGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    var showResort by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        PwdrMap(locationEnabled = permissionGranted)

        // Gebietsseite mit Lawinen, Wetter, Schnee und Webcams (PLAN.md 5d)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(8.dp)
                .clickable { showResort = true },
        ) {
            Text(
                text = "Gebiet",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (!permissionGranted) {
            PermissionCard(
                denied = permissionAsked,
                onRequest = {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(16.dp),
            )
        }

        FriendsLegend(
            Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .padding(8.dp),
        )

        // Oben links, damit die Quellenangabe auf der hellen Karte lesbar bleibt
        // und nicht von der Berechtigungskarte verdeckt wird.
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(8.dp),
        ) {
            Text(
                text = stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (showResort) ResortScreen(onBack = { showResort = false })
    }
}

@Composable
private fun PermissionCard(
    denied: Boolean,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.location_permission_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (denied) R.string.location_permission_denied else R.string.location_permission_text,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRequest) {
                Text(stringResource(R.string.location_permission_button))
            }
        }
    }
}

/** MapView, dessen Lebenszyklus an die Compose-Umgebung gebunden ist. */
@Composable
internal fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context) }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // Der Lifecycle ist bereits gestartet, wenn die Composition entsteht.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }
    return mapView
}

/** Blendet Pisten und Lifte aus der lokalen OSM-Kopie ein und aktualisiert sie, wenn ein Gebiet nachgeladen wird. */
@Composable
internal fun OsmLayersEffect(style: Style?) {
    val context = LocalContext.current
    LaunchedEffect(style) {
        val st = style ?: return@LaunchedEffect
        val db = PwdrDatabase.get(context)
        db.osm().observeLoadTimes().collect {
            val (pistes, lifts) = withContext(Dispatchers.IO) {
                val ways = try {
                    OsmRepository(db).waysIn(-90.0, 90.0, -180.0, 180.0)
                } catch (e: Exception) {
                    emptyList()
                }
                OsmGeoJson.build(ways, WayKind.PISTE) to OsmGeoJson.build(ways, WayKind.LIFT)
            }
            addOsmLayers(st, pistes, lifts)
        }
    }
}

/** Freunde als Punkte in ihrer Profilfarbe, ausgegraut wenn die Position älter als 60 s ist. */
@Composable
internal fun FriendsLayerEffect(style: Style?) {
    val live by Social.live.collectAsState()
    val friends by Social.friends.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(10_000)
            value = System.currentTimeMillis()
        }
    }
    LaunchedEffect(style, live, friends, now) {
        val st = style ?: return@LaunchedEffect
        val json = FriendsOnMap.geoJson(FriendsOnMap.markers(live, friends, now))
        val data = FeatureCollection.fromJson(json)
        val existing = st.getSourceAs<GeoJsonSource>("friends")
        if (existing != null) {
            existing.setGeoJson(data)
            return@LaunchedEffect
        }
        st.addSource(GeoJsonSource("friends", data))
        st.addLayer(
            CircleLayer("friends-dot", "friends").withProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleOpacity(Expression.switchCase(Expression.get("stale"), Expression.literal(0.4f), Expression.literal(1f))),
                PropertyFactory.circleStrokeOpacity(Expression.switchCase(Expression.get("stale"), Expression.literal(0.4f), Expression.literal(1f))),
            ),
        )
    }
}

/** Kleine Liste der sichtbaren Freunde mit Alter der Position. */
@Composable
internal fun FriendsLegend(modifier: Modifier = Modifier) {
    val live by Social.live.collectAsState()
    val friends by Social.friends.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(10_000)
            value = System.currentTimeMillis()
        }
    }
    val markers = FriendsOnMap.markers(live, friends, now)
    if (markers.isEmpty()) return
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            markers.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(
                                parseColor(m.color).copy(alpha = if (m.stale) 0.4f else 1f),
                                androidx.compose.foundation.shape.CircleShape,
                            ),
                    )
                    Text(
                        "${m.name} · ${m.ageText}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (m.stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Pistenfarben wie auf Schweizer Pistenplänen. Lifte als gestrichelte dunkle Linie. */
private fun addOsmLayers(style: Style, pistesJson: String, liftsJson: String) {
    val existing = style.getSourceAs<GeoJsonSource>("pistes")
    if (existing != null) {
        existing.setGeoJson(FeatureCollection.fromJson(pistesJson))
        style.getSourceAs<GeoJsonSource>("lifts")?.setGeoJson(FeatureCollection.fromJson(liftsJson))
        return
    }
    style.addSource(GeoJsonSource("pistes", FeatureCollection.fromJson(pistesJson)))
    style.addSource(GeoJsonSource("lifts", FeatureCollection.fromJson(liftsJson)))
    val color = Expression.match(
        Expression.get("difficulty"),
        Expression.color(android.graphics.Color.parseColor("#7A7A7A")),
        Expression.stop("novice", Expression.color(android.graphics.Color.parseColor("#1E73FF"))),
        Expression.stop("easy", Expression.color(android.graphics.Color.parseColor("#1E73FF"))),
        Expression.stop("intermediate", Expression.color(android.graphics.Color.parseColor("#E5322D"))),
        Expression.stop("advanced", Expression.color(android.graphics.Color.parseColor("#111111"))),
        Expression.stop("expert", Expression.color(android.graphics.Color.parseColor("#111111"))),
        Expression.stop("freeride", Expression.color(android.graphics.Color.parseColor("#111111"))),
    )
    fun add(layer: LineLayer) {
        if (style.getLayer(TRACK_CASING_LAYER) != null) style.addLayerBelow(layer, TRACK_CASING_LAYER) else style.addLayer(layer)
    }
    add(
        LineLayer("pistes-casing", "pistes").withProperties(
            PropertyFactory.lineColor(android.graphics.Color.WHITE),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ).apply { minZoom = 11f },
    )
    add(
        LineLayer("pistes", "pistes").withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ).apply { minZoom = 11f },
    )
    add(
        LineLayer("lifts", "lifts").withProperties(
            PropertyFactory.lineColor(android.graphics.Color.parseColor("#222222")),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ).apply { minZoom = 11f },
    )
}

@SuppressLint("MissingPermission")
@Composable
private fun PwdrMap(locationEnabled: Boolean) {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var cameraMoved by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            mapView.apply {
                onCreate(null)
                getMapAsync { m ->
                    m.uiSettings.isAttributionEnabled = false
                    m.uiSettings.isLogoEnabled = false
                    m.cameraPosition = CameraPosition.Builder()
                        .target(SWITZERLAND)
                        .zoom(7.0)
                        .build()
                    m.setStyle(Style.Builder().fromJson(SwisstopoStyle.json)) { s ->
                        style = s
                    }
                    map = m
                }
            }
        },
    )

    OsmLayersEffect(style)
    FriendsLayerEffect(style)

    // Standort-Punkt aktivieren, sobald Berechtigung und Stil vorhanden sind.
    DisposableEffect(map, style, locationEnabled) {
        val m = map
        val s = style
        if (m != null && s != null && locationEnabled) {
            val options = LocationComponentActivationOptions.builder(context, s)
                .locationComponentOptions(
                    LocationComponentOptions.builder(context)
                        .pulseEnabled(true)
                        .build(),
                )
                .useDefaultLocationEngine(true)
                .build()
            m.locationComponent.apply {
                activateLocationComponent(options)
                isLocationComponentEnabled = true
                renderMode = RenderMode.COMPASS
                cameraMode = CameraMode.NONE
                lastKnownLocation?.let { loc ->
                    if (!cameraMoved) {
                        m.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 13.0),
                        )
                        cameraMoved = true
                    }
                }
            }
        }
        onDispose { }
    }
}
