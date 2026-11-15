package app.pwdr.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

internal const val APRES_ICON = "apres-icon"

/**
 * Kaffee mit Sahne als Kartensymbol für Après-Ski: runde Plakette, Tasse mit Henkel, Dampf und eine Haube Sahne.
 * Wird einmal gezeichnet und als Bild in den Kartenstil gelegt.
 */
internal fun apresIcon(size: Int = 112): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val u = size / 112f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)

    // Plakette: warmes Weiss mit dunklem Rand (wie die Punkte der anderen Meldungen)
    p.style = Paint.Style.FILL
    p.color = 0xFFFFF1DC.toInt()
    c.drawCircle(56f * u, 56f * u, 52f * u, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = 5f * u
    p.color = 0xFF0E1317.toInt()
    c.drawCircle(56f * u, 56f * u, 52f * u, p)

    // Henkel
    p.style = Paint.Style.STROKE
    p.strokeWidth = 6f * u
    p.strokeCap = Paint.Cap.ROUND
    p.color = 0xFF6F4E37.toInt()
    c.drawArc(RectF(66f * u, 52f * u, 88f * u, 74f * u), -80f, 200f, false, p)

    // Untertasse
    p.style = Paint.Style.FILL
    p.color = 0xFF8D6A4F.toInt()
    c.drawRoundRect(RectF(26f * u, 78f * u, 84f * u, 84f * u), 3f * u, 3f * u, p)

    // Tasse
    p.color = 0xFF6F4E37.toInt()
    val cup = Path().apply {
        moveTo(32f * u, 52f * u)
        lineTo(78f * u, 52f * u)
        lineTo(74f * u, 72f * u)
        quadTo(72f * u, 79f * u, 64f * u, 79f * u)
        lineTo(46f * u, 79f * u)
        quadTo(38f * u, 79f * u, 36f * u, 72f * u)
        close()
    }
    c.drawPath(cup, p)

    // Sahnehaube: drei Wölkchen übereinander, oben mit Spitze
    p.color = 0xFFFFFFFF.toInt()
    c.drawOval(RectF(32f * u, 42f * u, 78f * u, 58f * u), p)
    c.drawOval(RectF(38f * u, 33f * u, 72f * u, 48f * u), p)
    c.drawOval(RectF(45f * u, 26f * u, 65f * u, 39f * u), p)
    val tip = Path().apply {
        moveTo(50f * u, 28f * u)
        quadTo(55f * u, 14f * u, 60f * u, 28f * u)
        close()
    }
    c.drawPath(tip, p)
    // Dünner Schatten unter der Sahne, damit sie sich vom Kaffeerand abhebt
    p.style = Paint.Style.STROKE
    p.strokeWidth = 2f * u
    p.color = 0xFFD9C6AE.toInt()
    c.drawArc(RectF(32f * u, 42f * u, 78f * u, 58f * u), 20f, 140f, false, p)

    return bmp
}
