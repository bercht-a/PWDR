package app.pwdr.social

/** Texte zur Anmeldung, ohne Android, damit sie sich testen lassen. */
object AuthTexts {
    const val SIGNUP_CLOSED =
        "Neue Anmeldungen sind gesperrt. Frag den Entwickler nach einer Einladung. " +
            "Hast du schon eine Einladung, tippe den Link in der Einladungs-Mail an."

    const val CAPTCHA_FAILED = "Die Sicherheitsprüfung (Captcha) ist fehlgeschlagen. Bitte noch einmal versuchen."

    /** Supabase lehnt den Link ab, weil das Captcha fehlt oder ungültig ist (captcha_failed, "captcha verification process failed"). */
    fun isCaptchaFailed(errorText: String): Boolean {
        val t = errorText.lowercase()
        return "captcha_failed" in t || "captcha verification" in t || "captcha protection" in t
    }

    /**
     * Erkennt die Antwort von Supabase, wenn "Allow new users to sign up" aus ist und die Adresse noch kein Konto hat
     * (otp_disabled, signup_disabled oder "Signups not allowed for ...").
     */
    fun isSignupClosed(errorText: String): Boolean {
        val t = errorText.lowercase()
        return "otp_disabled" in t || "signup_disabled" in t || "signups not allowed" in t || "signup is disabled" in t
    }
}
