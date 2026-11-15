package app.pwdr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.pwdr.data.OsmRepository
import app.pwdr.data.PwdrDatabase
import app.pwdr.data.TrackingRepository
import app.pwdr.osm.Resorts
import app.pwdr.social.Social
import app.pwdr.ui.AppRoot
import app.pwdr.ui.theme.PwdrTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Social.handleDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapLibre.getInstance(this)
        Social.start()
        Social.handleDeepLink(intent)

        // Aufzeichnungen, die nicht sauber beendet wurden (Absturz), nachträglich auswerten.
        // Nicht ausführen, solange gerade aufgezeichnet wird.
        lifecycleScope.launch(Dispatchers.IO) {
            // Pisten und Lifte laden (nur wenn sie fehlen oder alt sind). Ohne Netz bleibt alles beim Alten.
            val osm = OsmRepository(PwdrDatabase.get(this@MainActivity))
            for (resort in Resorts.ALL) osm.ensureLoaded(resort)
            if (!app.pwdr.tracking.TrackingStore.recording.value) {
                TrackingRepository(PwdrDatabase.get(this@MainActivity)).recoverUnfinished()
            }
        }

        setContent {
            PwdrTheme {
                AppRoot()
            }
        }
    }
}
