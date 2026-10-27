package com.autodialer.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.autodialer.app.databinding.ActivityLoginBinding

/**
 * Login / sign up: one tap "Continue with Google" (no password), or an email address + a 6-digit code sent to it.
 * A new account is created automatically; a correct code makes this device the only active device.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authManager: AuthManager
    private val handler = Handler(Looper.getMainLooper())
    private var slowHintRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(this)

        if (authManager.isLoggedIn()) {
            goToMain()
            return
        }

        binding.tvVersion.text = "Login v3 - Google + Email"
        for (v in listOf<View>(binding.btnGoogle, binding.btnSendOtp, binding.btnVerifyOtp, binding.btnResendOtp, binding.btnChangeNumber)) {
            addPressFeedback(v)
        }

        binding.btnGoogle.setOnClickListener { signInWithGoogle() }
        binding.btnSendOtp.setOnClickListener { requestOtp() }
        binding.btnResendOtp.setOnClickListener { requestOtp() }
        binding.btnVerifyOtp.setOnClickListener { verifyOtp() }
        binding.btnChangeNumber.setOnClickListener { backToPhoneEntry() }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSlowHint()
        stopCountdown()
    }

    /** Buttons visibly react when tapped (they shrink/dim a little) so it is clear the tap registered. */
    private fun addPressFeedback(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.75f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            }
            false // do not consume the touch - the click itself must still happen
        }
    }

    /** Shows a message under the buttons. Errors are red AND shown as a pop-up, so they cannot be missed. */
    private fun showStatus(message: String, isError: Boolean) {
        binding.tvLoginStatus.text = message
        binding.tvLoginStatus.setTextColor(if (isError) 0xFFD93025.toInt() else 0xFF8A8FA3.toInt())
        if (isError && message.isNotBlank()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private val emailPattern = Regex("^[a-z0-9._%+\\-]+@[a-z0-9\\-]+(\\.[a-z0-9\\-]+)*\\.[a-z]{2,}$")

    /** What the user typed, checked and turned into the account id (a lower-case email), or null. */
    private fun fullPhone(): String? {
        val email = binding.etPhone.text.toString().trim().lowercase()
        if (email.isEmpty()) {
            showStatus("Enter your email address", true)
            return null
        }
        if (!emailPattern.matches(email)) {
            showStatus("Enter a valid email address", true)
            return null
        }
        return email
    }

    private fun requestOtp() {
        if (busy) return
        val phone = fullPhone() ?: return
        busy = true
        binding.btnSendOtp.isEnabled = false
        binding.btnResendOtp.isEnabled = false
        showStatus("Sending code...", false)
        startSlowHint("Still sending - the server can take a few extra seconds, hang on...")
        authManager.sendOtp(phone) { success, message ->
            busy = false
            cancelSlowHint()
            binding.btnSendOtp.isEnabled = true
            if (success) {
                showOtpStep()
                showStatus("Code sent to $phone. Check Inbox, Spam and Promotions - the email can take a minute.", false)
                startResendCountdown(60)
            } else {
                showStatus(message, true)
                // keep resend usable if we were already on the OTP step
                if (binding.otpSection.visibility == View.VISIBLE) binding.btnResendOtp.isEnabled = true
            }
        }
    }

    private fun verifyOtp() {
        if (busy) return
        val phone = fullPhone() ?: return
        val otp = binding.etOtp.text.toString().filter { it.isDigit() }
        if (otp.length != 6) {
            showStatus("Enter the 6-digit code", true)
            return
        }
        busy = true
        binding.btnVerifyOtp.isEnabled = false
        showStatus("Verifying...", false)
        startSlowHint("Still verifying - the server can take a few extra seconds, hang on...")
        authManager.verifyOtp(phone, otp) { success, message ->
            busy = false
            cancelSlowHint()
            binding.btnVerifyOtp.isEnabled = true
            if (success) {
                authManager.setLoggedIn(phone)
                goToMain()
            } else {
                showStatus(message, true)
            }
        }
    }

    /** One tap "Continue with Google": no password and no code. Google gives the app an ID token,
     * the backend asks Google to verify it, and the verified email becomes the account. */
    private fun signInWithGoogle() {
        if (busy) return
        if (!GoogleConfig.isConfigured()) {
            showStatus("Google sign-in is not set up yet (the Client ID is missing in GoogleConfig.kt). Please use your email below.", true)
            return
        }
        busy = true
        binding.btnGoogle.isEnabled = false
        showStatus("Opening Google...", false)
        val option = GetSignInWithGoogleOption.Builder(GoogleConfig.WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        CredentialManager.create(this).getCredentialAsync(
            this,
            request,
            null,
            ContextCompat.getMainExecutor(this),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    handleGoogleResult(result)
                }

                override fun onError(e: GetCredentialException) {
                    googleFailed(e)
                }
            }
        )
    }

    private fun handleGoogleResult(result: GetCredentialResponse) {
        val credential = result.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            try {
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                showStatus("Signing you in...", false)
                startSlowHint("Still signing in - the server can take a few extra seconds, hang on...")
                authManager.googleLogin(token) { success, accountId, message ->
                    busy = false
                    cancelSlowHint()
                    binding.btnGoogle.isEnabled = true
                    if (success) {
                        authManager.setLoggedIn(accountId)
                        goToMain()
                    } else {
                        showStatus(message, true)
                    }
                }
            } catch (e: GoogleIdTokenParsingException) {
                finishGoogleWithError("Google sign-in failed. Please try again.")
            }
        } else {
            finishGoogleWithError("Google sign-in failed. Please try again.")
        }
    }

    private fun googleFailed(e: GetCredentialException) {
        val msg = when (e) {
            is GetCredentialCancellationException -> "Sign-in cancelled"
            is NoCredentialException -> "No Google account found on this phone. Add one in Settings, or use your email below."
            else -> "Google sign-in failed: ${e.message?.take(150) ?: e.type}"
        }
        finishGoogleWithError(msg)
    }

    private fun finishGoogleWithError(message: String) {
        busy = false
        cancelSlowHint()
        binding.btnGoogle.isEnabled = true
        showStatus(message, true)
    }

    private fun showOtpStep() {
        binding.otpSection.visibility = View.VISIBLE
        binding.btnGoogle.visibility = View.GONE
        binding.tvOrDivider.visibility = View.GONE
        binding.btnSendOtp.visibility = View.GONE
        binding.etPhone.isEnabled = false
        binding.etOtp.requestFocus()
    }

    private fun backToPhoneEntry() {
        stopCountdown()
        binding.otpSection.visibility = View.GONE
        binding.btnGoogle.visibility = View.VISIBLE
        binding.tvOrDivider.visibility = View.VISIBLE
        binding.btnSendOtp.visibility = View.VISIBLE
        binding.etPhone.isEnabled = true
        binding.etOtp.text?.clear()
        showStatus("", false)
    }

    private fun startResendCountdown(seconds: Int) {
        stopCountdown()
        var left = seconds
        val runnable = object : Runnable {
            override fun run() {
                if (left > 0) {
                    binding.btnResendOtp.isEnabled = false
                    binding.btnResendOtp.alpha = 0.5f
                    binding.btnResendOtp.text = "Resend code in ${left}s"
                    left--
                    handler.postDelayed(this, 1000)
                } else {
                    binding.btnResendOtp.isEnabled = true
                    binding.btnResendOtp.alpha = 1f
                    binding.btnResendOtp.text = "Resend code"
                }
            }
        }
        countdownRunnable = runnable
        handler.post(runnable)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        binding.btnResendOtp.alpha = 1f
        binding.btnResendOtp.text = "Resend code"
    }

    /** Shows a reassuring message if the backend hasn't responded within a few seconds
     * (the free backend can be slow to "wake up" after being idle). */
    private fun startSlowHint(message: String) {
        cancelSlowHint()
        val runnable = Runnable { showStatus(message, false) }
        slowHintRunnable = runnable
        handler.postDelayed(runnable, 4000)
    }

    private fun cancelSlowHint() {
        slowHintRunnable?.let { handler.removeCallbacks(it) }
        slowHintRunnable = null
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
