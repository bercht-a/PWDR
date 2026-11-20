package app.pwdr.captcha

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import app.pwdr.social.SupabaseConfig
import com.hcaptcha.sdk.HCaptcha
import com.hcaptcha.sdk.HCaptchaConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Fehler beim Captcha, mit einem Text, den man anzeigen kann. */
class CaptchaException(message: String) : Exception(message)

/**
 * hCaptcha vor dem Anmelde-Link. Ohne eingetragenen Schlüssel ([SupabaseConfig.HCAPTCHA_SITE_KEY]) ist das Captcha aus und
 * [token] liefert null. Das Token gilt nur kurz (etwa 2 Minuten) und nur einmal, also erst beim Tippen auf "Link senden" holen.
 */
object Captcha {
    val enabled: Boolean get() = SupabaseConfig.HCAPTCHA_SITE_KEY.isNotBlank()

    /** Sucht die Activity hinter einem Compose-Kontext. */
    fun activityOf(context: Context): FragmentActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is FragmentActivity) return c
            c = c.baseContext
        }
        return null
    }

    /** Holt ein Token (zeigt bei Bedarf die Aufgabe von hCaptcha). Null, wenn das Captcha aus ist. Muss im UI-Thread laufen. */
    suspend fun token(activity: FragmentActivity): String? {
        if (!enabled) return null
        return suspendCancellableCoroutine { cont ->
            try {
                HCaptcha.getClient(activity)
                    .setup(HCaptchaConfig.builder().siteKey(SupabaseConfig.HCAPTCHA_SITE_KEY).build())
                    .verifyWithHCaptcha()
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it.tokenResult) }
                    .addOnFailureListener { if (cont.isActive) cont.resumeWithException(CaptchaException(it.message ?: "Captcha fehlgeschlagen")) }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(CaptchaException(e.message ?: "Captcha fehlgeschlagen"))
            }
        }
    }
}
