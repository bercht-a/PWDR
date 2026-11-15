package app.pwdr.ui

import java.util.Locale

private val DE = Locale.GERMAN

fun kmh(ms: Double): Int = Math.round(ms * 3.6).toInt()

fun formatDistance(m: Double): String =
    if (m < 1000) String.format(DE, "%d m", Math.round(m)) else String.format(DE, "%.1f km", m / 1000.0)

fun formatMeters(m: Double): String = String.format(DE, "%d m", Math.round(m))

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val min = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(DE, "%d:%02d:%02d", h, min, s) else String.format(DE, "%d:%02d", min, s)
}
