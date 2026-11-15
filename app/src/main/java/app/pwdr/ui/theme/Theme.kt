package app.pwdr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Farben aus PLAN.md, Abschnitt 5a. Standard ist Dunkel.
private val DarkColors = darkColorScheme(
    background = Color(0xFF0E1317),
    surface = Color(0xFF171E24),
    surfaceVariant = Color(0xFF1F2830),
    onBackground = Color(0xFFE8EEF2),
    onSurface = Color(0xFFE8EEF2),
    onSurfaceVariant = Color(0xFF8A9AA6),
    primary = Color(0xFF4CC9F0),
    onPrimary = Color(0xFF0E1317),
    secondary = Color(0xFF2EC4B6),
    onSecondary = Color(0xFF0E1317),
    error = Color(0xFFFF5A5F),
)

private val LightColors = lightColorScheme(
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EEF2),
    onBackground = Color(0xFF10181E),
    onSurface = Color(0xFF10181E),
    onSurfaceVariant = Color(0xFF52626E),
    primary = Color(0xFF0A8FB8),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF13917F),
    onSecondary = Color(0xFFFFFFFF),
    error = Color(0xFFC62F34),
)

@Composable
fun PwdrTheme(
    // Dunkel ist der Standard. Die Einstellung Dunkel/Hell/System kommt später.
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
