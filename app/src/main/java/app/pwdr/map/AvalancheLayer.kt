package app.pwdr.map

import android.graphics.Color as AColor
import android.graphics.PointF
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pwdr.resort.AvalancheApi
import app.pwdr.resort.ResortProfiles
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.net.URI

/**
 * Lawinenregionen des SLF als Fläche auf der Karte (PLAN.md Phase 6, offen gewesen). Die Polygone und die Farbe der
 * Gefahrenstufe kommen von der SLF-Schnittstelle selbst (GeoJSON des Bulletins). Regionen ohne Bulletin bleiben ohne Fläche.
 * Die Ebene lädt erst, wenn sie eingeschaltet wird. Sie ist eine Übersicht und ersetzt das Bulletin nicht.
 */
internal object AvalancheLayer {
    const val URL = "https://aws.slf.ch/api/bulletin/caaml/de/geojson"
    const val SOURCE = "avalanche"
    const val FILL = "avalanche-fill"
    const val LINE = "avalanche-line"
}

/** Eine angetippte Lawinenregion. [level] ist die höchste Stufe des Tages, null ohne Bulletin. */
internal data class AvalancheRegion(val name: String, val level: Int?, val fill: String?, val validUntil: String?)

/** Schaltet die Ebene ein oder aus. Beim ersten Einschalten werden Quelle und Ebenen angelegt (über der Hangneigung, unter Pisten). */
internal fun applyAvalanche(style: Style, on: Boolean) {
    if (on && style.getSource(AvalancheLayer.SOURCE) == null) {
        try {
            style.addSource(GeoJsonSource(AvalancheLayer.SOURCE, URI(AvalancheLayer.URL)))
            val fill = FillLayer(AvalancheLayer.FILL, AvalancheLayer.SOURCE).withProperties(
                // Ohne Bulletin ist fill null: coalesce fällt dann auf durchsichtig zurück.
                PropertyFactory.fillColor(
                    Expression.coalesce(Expression.toColor(Expression.get("fill")), Expression.rgba(0, 0, 0, 0f)),
                ),
                PropertyFactory.fillOpacity(0.38f),
            )
            val line = LineLayer(AvalancheLayer.LINE, AvalancheLayer.SOURCE).withProperties(
                PropertyFactory.lineColor(AColor.parseColor("#66000000")),
                PropertyFactory.lineWidth(1f),
            )
            val anchor = SwisstopoStyle.SLOPE_LAYER
            if (style.getLayer(anchor) != null) {
                style.addLayerAbove(fill, anchor)
                style.addLayerAbove(line, AvalancheLayer.FILL)
            } else {
                style.addLayer(fill)
                style.addLayer(line)
            }
        } catch (_: Exception) {
            return
        }
    }
    val vis = PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
    style.getLayer(AvalancheLayer.FILL)?.setProperties(vis)
    style.getLayer(AvalancheLayer.LINE)?.setProperties(vis)
}

/** Lawinenregion an der angetippten Stelle. Null, wenn die Ebene aus ist oder dort keine Region liegt. */
internal fun avalancheAt(map: MapLibreMap, latLng: LatLng): AvalancheRegion? {
    if (map.style?.getLayer(AvalancheLayer.FILL) == null) return null
    val pt: PointF = map.projection.toScreenLocation(latLng)
    val f = map.queryRenderedFeatures(pt, AvalancheLayer.FILL).firstOrNull() ?: return null
    return try {
        val name = f.getProperty("regions")?.asJsonArray?.firstOrNull()?.asJsonObject?.get("name")?.asString ?: "Lawinenregion"
        val level = f.getProperty("dangerRatings")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { AvalancheApi.dangerLevel(it.asJsonObject.get("mainValue")?.asString) }?.maxOrNull()
        val fill = f.getProperty("fill")?.takeIf { it.isJsonPrimitive }?.asString
        val until = f.getProperty("validTime")?.takeIf { it.isJsonObject }?.asJsonObject?.get("endTime")?.asString
        AvalancheRegion(name, level, fill, until)
    } catch (_: Exception) {
        null
    }
}

@Composable
internal fun AvalancheDialog(region: AvalancheRegion, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(region.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (region.level != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.size(18.dp).background(
                                try { Color(AColor.parseColor(region.fill ?: "#888888")) } catch (_: Exception) { Color.Gray },
                                androidx.compose.foundation.shape.CircleShape,
                            ),
                        )
                        Text(
                            "Stufe ${region.level} (${AvalancheApi.dangerName(region.level)}), höchste Stufe des Tages",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    Text("Für diese Region gibt der SLF zurzeit kein Bulletin aus. Das heisst nicht, dass keine Gefahr besteht.")
                }
                region.validUntil?.let { Text("Gültig bis: ${it.replace('T', ' ').removeSuffix("Z")} UTC", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    "Übersicht nach dem SLF-Bulletin. Höhenlage, Exposition und Lawinenprobleme stehen im offiziellen Bulletin. Vor Abfahrten abseits der Pisten immer dort nachlesen.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(ResortProfiles.BULLETIN_URL))
                } catch (_: Exception) {
                }
            }) { Text("Offizielles Bulletin") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schliessen") } },
    )
}
