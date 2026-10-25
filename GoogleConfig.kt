package com.autodialer.app

/**
 * "Sign in with Google" settings.
 *
 * WEB_CLIENT_ID is the "Web application" OAuth client id from Google Cloud Console
 * (Credentials -> Create credentials -> OAuth client ID -> Web application).
 * The SAME value must also be saved in Apps Script -> Project Settings -> Script Properties as
 * GOOGLE_WEB_CLIENT_ID. Full steps: GOOGLE_LOGIN_SETUP.md
 *
 * It is a public id, not a secret - it is fine for it to live in the app.
 */
object GoogleConfig {
    const val WEB_CLIENT_ID = "PASTE_YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"

    fun isConfigured(): Boolean = !WEB_CLIENT_ID.startsWith("PASTE_")
}
