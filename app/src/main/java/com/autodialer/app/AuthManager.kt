package com.autodialer.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Email + OTP login. The OTP email is sent and checked by the Google Apps Script backend
 * (see backend/Code.gs and OTP_SETUP.md); this app never generates or sees the OTP itself.
 *  - Logging in with a correct OTP makes this device the ONLY active device for the email -
 *    any other device that was logged in gets signed out (detected via
 *    checkSessionInBackground, called periodically while the app is open).
 */
class AuthManager(private val context: Context) {

    private val prefs = SecurePrefs.open(context, "autodialer_auth")
    private val mainHandler = Handler(Looper.getMainLooper())

    fun deviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    /** A session saved by the old phone-number login has no email, so it counts as logged out: the user logs in again with email. */
    fun isLoggedIn(): Boolean = prefs.getBoolean("loggedIn", false) && email().isNotEmpty()
    fun email(): String = prefs.getString("email", "") ?: ""

    fun setLoggedIn(email: String) {
        prefs.edit().clear().putBoolean("loggedIn", true).putString("email", email).apply()   // clear(): drops any leftover phone-era data
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    /** Asks the backend to email a 6-digit OTP to this address. */
    fun sendOtp(email: String, onResult: (success: Boolean, message: String) -> Unit) {
        if (SubscriptionManager.SCRIPT_URL.startsWith("PASTE_")) {
            onResult(false, "Backend not set up yet - follow backend/SETUP.md")
            return
        }
        Thread {
            try {
                val url = "${SubscriptionManager.SCRIPT_URL}?action=sendOtp" +
                    "&email=${URLEncoder.encode(email, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val json = JSONObject(httpGet(url))
                if (json.optString("status") == "ok") {
                    mainHandler.post { onResult(true, "OTP sent") }
                } else {
                    mainHandler.post { onResult(false, json.optString("message", "Could not send OTP")) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, "Check your internet and try again") }
            }
        }.start()
    }

    /** Checks the OTP. On success this device becomes the number's only active device. */
    fun verifyOtp(email: String, otp: String, onResult: (success: Boolean, message: String) -> Unit) {
        if (SubscriptionManager.SCRIPT_URL.startsWith("PASTE_")) {
            onResult(false, "Backend not set up yet - follow backend/SETUP.md")
            return
        }
        Thread {
            try {
                val url = "${SubscriptionManager.SCRIPT_URL}?action=verifyOtp" +
                    "&email=${URLEncoder.encode(email, "UTF-8")}" +
                    "&otp=${URLEncoder.encode(otp, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val json = JSONObject(httpGet(url))
                if (json.optString("status") == "ok") {
                    mainHandler.post { onResult(true, "Logged in") }
                } else {
                    mainHandler.post { onResult(false, json.optString("message", "Login failed")) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, "Check your internet and try again") }
            }
        }.start()
    }

    /** Checks whether this device is still the active session for the logged-in email.
     * Throttled to at most once a minute - this hits the (slow, free) backend, so
     * calling it on every single onResume made the app feel sluggish overall for no benefit. */
    fun checkSessionInBackground(onLoggedOutElsewhere: () -> Unit) {
        if (SubscriptionManager.SCRIPT_URL.startsWith("PASTE_")) return
        val email = email()
        if (email.isEmpty()) return
        val now = System.currentTimeMillis()
        val lastChecked = prefs.getLong("lastSessionCheck", 0L)
        if (now - lastChecked < 60 * 1000) return
        prefs.edit().putLong("lastSessionCheck", now).apply()
        Thread {
            try {
                val url = "${SubscriptionManager.SCRIPT_URL}?action=checkSession" +
                    "&email=${URLEncoder.encode(email, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val response = httpGet(url)
                val json = JSONObject(response)
                if (json.optString("status") == "ok" && !json.optBoolean("active", true)) {
                    mainHandler.post { onLoggedOutElsewhere() }
                }
            } catch (e: Exception) {
                // Offline - don't force a logout, just skip this check.
            }
        }.start()
    }

    private fun httpGet(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        // Apps Script "cold starts" after being idle can genuinely take several seconds - this
        // just needs to be long enough not to cut off a real (if slow) response.
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader()?.use { it.readText() } ?: "{\"status\":\"error\",\"message\":\"Empty response (HTTP $code)\"}"
        } finally {
            conn.disconnect()
        }
    }
}
