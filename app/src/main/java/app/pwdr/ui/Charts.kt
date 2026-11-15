package app.pwdr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwdr.tracking.Activity
import app.pwdr.tracking.DayProfile
import app.pwdr.tracking.ProfilePoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Metriken der Statistik. G-Force ist ein Richtwert und hängt von der Trageposition des Handys ab. */
enum class Metric(val label: String, val unit: String) {
    VERT("Höhenmeter", "m"),
    DIST("Distanz", "m"),
    SPEED("Speed", "km/h"),
    G("G-Force", "g"),
    ;

    /** Farbe der Metrik, in allen Diagrammen gleich (PLAN.md): Eisblau, Türkis, Orange, Violett. */
    val color: Color
        get() = when (this) {
            VERT -> Color(0xFF4CC9F0)
            DIST -> Color(0xFF2EC4B6)
            SPEED -> Color(0xFFFF9F1C)
            G -> Color(0xFF9B5DE5)
        }

    /** Wert eines Profilpunkts für die Tagesgrafik. */
    fun value(p: ProfilePoint): Double = when (this) {
        VERT -> p.altM
        DIST -> p.cumDistM
        SPEED -> p.speedMs * 3.6
        G -> p.g ?: 0.0
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)

/**
 * Tagesverlauf als Linie über der Zeit. Abfahrten farbig, Lifte grau, Pausen gedimmt.
 * Lücken zwischen Aufzeichnungen (Mittagspause) werden gestaucht und die Linie unterbrochen.
 * Bei Speed ist der Höchstwert markiert.
 */
@Composable
fun DayChart(
    points: List<ProfilePoint>,
    metric: Metric,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val downhillColor = metric.color
    val liftColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pauseColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val markColor = MaterialTheme.colorScheme.onSurface
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = textColor, fontSize = 11.sp)
    val zone = ZoneId.systemDefault()

    Canvas(modifier.fillMaxWidth().height(height)) {
        if (points.size < 2) return@Canvas
        val left = 44.dp.toPx()
        val right = 8.dp.toPx()
        val top = 16.dp.toPx()
        val bottom = 20.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom

        // X-Positionen: echte Zeit, aber Lücken auf GAP_VISUAL_MS gestaucht.
        val xs = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val dt = points[i].timeMs - points[i - 1].timeMs
            xs[i] = xs[i - 1] + if (dt > DayProfile.GAP_MS) GAP_VISUAL_MS else dt.toDouble()
        }
        val xMax = xs.last().coerceAtLeast(1.0)

        val values = points.map { metric.value(it) }
        var vMin = values.min()
        var vMax = values.max()
        if (metric != Metric.VERT) vMin = 0.0
        if (vMax - vMin < (if (metric == Metric.G) 0.5 else 1.0)) vMax = vMin + (if (metric == Metric.G) 0.5 else 1.0)
        val pad = (vMax - vMin) * 0.05
        val lo = if (metric == Metric.VERT) vMin - pad else vMin
        val hi = vMax + pad

        fun px(i: Int) = left + (xs[i] / xMax * w).toFloat()
        fun py(v: Double) = top + (h - ((v - lo) / (hi - lo) * h)).toFloat()

        // Hilfslinien oben, Mitte, unten
        for (f in listOf(0.0, 0.5, 1.0)) {
            val v = lo + (hi - lo) * f
            val y = py(v)
            drawLine(gridColor, Offset(left, y), Offset(left + w, y), strokeWidth = 1.dp.toPx())
            val text = if (metric == Metric.G) String.format(Locale.GERMAN, "%.1f", v) else String.format(Locale.GERMAN, "%d", Math.round(v))
            val m = measurer.measure(text, label)
            drawText(m, topLeft = Offset(left - m.size.width - 6.dp.toPx(), y - m.size.height / 2f))
        }

        // Zeitachse: Start und Ende
        val startText = Instant.ofEpochMilli(points.first().timeMs).atZone(zone).format(CLOCK)
        val endText = Instant.ofEpochMilli(points.last().timeMs).atZone(zone).format(CLOCK)
        val ms = measurer.measure(startText, label)
        val me = measurer.measure(endText, label)
        drawText(ms, topLeft = Offset(left, size.height - ms.size.height.toFloat()))
        drawText(me, topLeft = Offset(left + w - me.size.width, size.height - me.size.height.toFloat()))

        // Linienstücke nach Aktivität
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (b.timeMs - a.timeMs > DayProfile.GAP_MS) continue
            val color = when (b.activity) {
                Activity.DOWNHILL -> downhillColor
                Activity.LIFT -> liftColor
                Activity.PAUSE -> pauseColor
            }
            drawLine(
                color,
                Offset(px(i - 1), py(values[i - 1])),
                Offset(px(i), py(values[i])),
                strokeWidth = stroke.width,
                cap = stroke.cap,
            )
        }

        if (metric == Metric.SPEED || metric == Metric.G) {
            val iMax = values.indices.maxBy { values[it] }
            val c = Offset(px(iMax), py(values[iMax]))
            drawCircle(markColor, radius = 4.dp.toPx(), center = c)
            val t = measurer.measure(
                if (metric == Metric.G) String.format(Locale.GERMAN, "%.1f g", values[iMax])
                else String.format(Locale.GERMAN, "%d km/h", Math.round(values[iMax])),
                label.copy(color = markColor),
            )
            val x = (c.x - t.size.width / 2f).coerceIn(left, left + w - t.size.width)
            drawText(t, topLeft = Offset(x, (c.y - t.size.height - 4.dp.toPx()).coerceAtLeast(0f)))
        }
    }
}

private const val GAP_VISUAL_MS = 60_000.0

/** Horizontaler Balken, [fraction] zwischen 0 und 1. */
@Composable
fun BarFill(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        drawRoundRect(
            color,
            size = androidx.compose.ui.geometry.Size(size.width * fraction.coerceIn(0f, 1f), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
        )
    }
}
