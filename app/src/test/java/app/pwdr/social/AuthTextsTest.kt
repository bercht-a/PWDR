package app.pwdr.social

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTextsTest {
    @Test fun recognizesClosedSignupAnswers() {
        assertTrue(AuthTexts.isSignupClosed("""{"code":422,"error_code":"otp_disabled","msg":"Signups not allowed for otp"}"""))
        assertTrue(AuthTexts.isSignupClosed("Signups not allowed for this instance"))
        assertTrue(AuthTexts.isSignupClosed("error_code: signup_disabled"))
        assertTrue(AuthTexts.isSignupClosed("Signup is disabled"))
    }

    @Test fun otherErrorsAreNotTakenForClosedSignup() {
        assertFalse(AuthTexts.isSignupClosed(""))
        assertFalse(AuthTexts.isSignupClosed("over_email_send_rate_limit"))
        assertFalse(AuthTexts.isSignupClosed("Unable to resolve host"))
    }

    @Test fun messageTellsWhatToDo() {
        assertTrue(AuthTexts.SIGNUP_CLOSED.contains("Einladung"))
    }

    @Test fun recognizesCaptchaFailure() {
        assertTrue(AuthTexts.isCaptchaFailed("""{"error_code":"captcha_failed","msg":"captcha verification process failed"}"""))
        assertTrue(AuthTexts.isCaptchaFailed("captcha protection: request disallowed"))
        assertFalse(AuthTexts.isCaptchaFailed("otp_disabled"))
        assertFalse(AuthTexts.isCaptchaFailed(""))
        assertTrue(AuthTexts.CAPTCHA_FAILED.contains("Captcha"))
    }
}
