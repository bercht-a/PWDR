package app.pwdr.social

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Verbindung zum Supabase-Projekt (PLAN.md 5c). Der "publishable key" ist für Apps gedacht und darf im APK stehen,
 * die Zugriffsregeln der Datenbank (Row Level Security) schützen die Daten. Der geheime Schlüssel gehört nie in die App.
 */
object SupabaseConfig {
    const val URL = "https://yhvsssvenhrrumugoxke.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_nRYQt8E3QVMlCXsyWIdPig_oLeE-1qe"

    /** Adresse, über die der Link aus der Anmelde-E-Mail in die App zurückführt (muss in Supabase eingetragen sein). */
    const val AUTH_SCHEME = "pwdr"
    const val AUTH_HOST = "auth"

    /**
     * Öffentlicher Website-Schlüssel (sitekey) von hCaptcha für den Anmelde-Link. Leer = kein Captcha in der App.
     * Erst eintragen, wenn das Captcha auch in Supabase eingeschaltet ist (backend/README.md), sonst passt beides nicht zusammen.
     */
    const val HCAPTCHA_SITE_KEY = ""
}

object Backend {
    val client: SupabaseClient by lazy {
        createSupabaseClient(SupabaseConfig.URL, SupabaseConfig.PUBLISHABLE_KEY) {
            install(Auth) {
                scheme = SupabaseConfig.AUTH_SCHEME
                host = SupabaseConfig.AUTH_HOST
            }
            install(Postgrest)
            install(Realtime)
        }
    }
}

@Serializable
data class Profile(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("spoken_name") val spokenName: String? = null,
    val color: String = "#4CC9F0",
)

/** Zeile aus der Funktion list_friends(). */
@Serializable
data class Friend(
    @SerialName("friend_id") val friendId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("spoken_name") val spokenName: String? = null,
    val color: String = "#4CC9F0",
    /** accepted oder blocked (nur selbst Blockierte erscheinen). */
    val status: String,
)

/** Letzter bekannter Stand eines Freundes aus der Tabelle live_locations. */
@Serializable
data class LiveRow(
    @SerialName("user_id") val userId: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val alt: Double? = null,
    val speed: Double? = null,
    val heading: Double? = null,
    val accuracy: Double? = null,
    val state: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ShareSessionRow(
    val id: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
)

@Serializable
data class ShareTargetRow(
    @SerialName("session_id") val sessionId: String,
    @SerialName("friend_id") val friendId: String,
)
