package com.autodialer.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.autodialer.app.databinding.ActivityLoginBinding

/**
 * Login with email + OTP (mailed by the backend). A correct OTP makes this device
 * the only active device for that email.
 *
 * Improvements over the old version:
 *  - OTP step opens INSTANTLY on tap (no waiting for the slow server).
 *  - The pending OTP step survives leaving the app (e.g. to read the mail in Gmail) - no more
 *    going back to "enter email" and sending OTP again.
 *  - Every button gives touch feedback (press animation + vibration tick).
 *  - OTP auto-verifies as soon as 6 digits are entered; a freshly copied OTP is auto-pasted.
 *  - Resend wait is 30s (was 60s).
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authManager: AuthManager
    private val handler = Handler(Looper.getMainLooper())
    private var slowHintRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var busy = false

    private val pendingPrefs by lazy { SecurePrefs.open(applicationContext, "login_pending") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(this)

        if (authManager.isLoggedIn()) {
            goToMain()
            return
        }

        tap(binding.btnSendOtp) { requestOtp() }
        tap(binding.btnResendOtp) { requestOtp() }
        tap(binding.btnVerifyOtp) { verifyOtp() }
        tap(binding.btnChangeEmail) { backToEmailEntry() }

        // keyboard "Send" key on the email field = Send OTP
        binding.etEmail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                requestOtp(); true
            } else false
        }

        // auto-verify the moment 6 digits are in
        binding.etOtp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if ((s?.toString()?.count { it.isDigit() } ?: 0) == 6 && !busy) verifyOtp()
            }
        })

        restorePendingOtpStep()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSlowHint()
        stopCountdown()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pasteFreshOtpFromClipboard()
    }

    // ---------------------------------------------------------------- touch feedback

    /** Click handler + visible press animation + vibration tick, so every tap "feels" registered. */
    private fun tap(view: View, action: () -> Unit) {
        view.isClickable = true
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.75f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            }
            false // let the normal click still fire
        }
        view.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
    }

    // ---------------------------------------------------------------- state restore

    private fun savePending(email: String) {
        pendingPrefs.edit().putString("email", email).putLong("sentAt", System.currentTimeMillis()).apply()
    }

    private fun clearPending() {
        pendingPrefs.edit().clear().apply()
    }

    /** If an OTP was sent a few minutes ago, reopen straight on the OTP step. */
    private fun restorePendingOtpStep() {
        val email = pendingPrefs.getString("email", null) ?: return
        val sentAt = pendingPrefs.getLong("sentAt", 0L)
        val ageMs = System.currentTimeMillis() - sentAt
        if (ageMs !in 0..(10 * 60 * 1000L)) { // OTP is valid for 10 min on the server
            clearPending(); return
        }
        binding.etEmail.setText(email)
        showOtpStep()
        binding.tvLoginStatus.text = "OTP sent to ${EmailValidator.mask(email)} - enter it below"
        val left = RESEND_SECONDS - (ageMs / 1000).toInt()
        if (left > 0) startResendCountdown(left) else stopCountdown()
    }

    /** If the user just copied the 6-digit code from the mail, fill it in automatically. */
    private fun pasteFreshOtpFromClipboard() {
        if (binding.otpSection.visibility != View.VISIBLE || busy) return
        if (!binding.etOtp.text.isNullOrEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return // can't tell if the copy is recent
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            val copiedAt = clip.description.timestamp
            val sentAt = pendingPrefs.getLong("sentAt", 0L)
            if (sentAt == 0L || copiedAt < sentAt) return // copied before this OTP was sent
            val text = clip.getItemAt(0)?.text?.toString() ?: return
            val code = Regex("\\b\\d{6}\\b").find(text)?.value ?: return
            binding.etOtp.setText(code) // the TextWatcher then auto-verifies
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- actions

    private fun validEmail(): String? {
        val email = EmailValidator.normalize(binding.etEmail.text.toString())
        if (email == null) binding.tvLoginStatus.text = "Enter a valid email address"
        return email
    }

    private fun requestOtp() {
        if (busy) return
        val email = validEmail() ?: return
        busy = true
        binding.btnSendOtp.isEnabled = false
        binding.btnResendOtp.isEnabled = false
        binding.btnResendOtp.alpha = 0.5f
        binding.btnResendOtp.text = "Sending..."

        // Open the OTP step immediately - do NOT make the user stare at the email screen while
        // the (slow, free) backend works.
        showOtpStep()
        binding.tvLoginStatus.text = "Sending OTP to ${EmailValidator.mask(email)}..."
        startSlowHint("Still sending - the server can take a few extra seconds, hang on...")

        authManager.sendOtp(email) { success, message ->
            busy = false
            cancelSlowHint()
            if (success) {
                savePending(email)
                binding.tvLoginStatus.text = "OTP sent to ${EmailValidator.mask(email)} - check inbox (and spam)"
                startResendCountdown(RESEND_SECONDS)
            } else {
                // stay on the OTP step so the user can just tap Resend
                stopCountdown()
                binding.btnResendOtp.isEnabled = true
                binding.tvLoginStatus.text = message
            }
        }
    }

    private fun verifyOtp() {
        if (busy) return
        val email = validEmail() ?: return
        val otp = binding.etOtp.text.toString().filter { it.isDigit() }
        if (otp.length != 6) {
            binding.tvLoginStatus.text = "Enter the 6-digit OTP"
            return
        }
        busy = true
        binding.btnVerifyOtp.isEnabled = false
        binding.tvLoginStatus.text = "Verifying..."
        startSlowHint("Still verifying - the server can take a few extra seconds, hang on...")
        authManager.verifyOtp(email, otp) { success, message ->
            busy = false
            cancelSlowHint()
            binding.btnVerifyOtp.isEnabled = true
            if (success) {
                clearPending()
                SubscriptionManager(this).bindAccount(email) // plan + trial follow the email, never the phone
                authManager.setLoggedIn(email)
                goToMain()
            } else {
                binding.tvLoginStatus.text = message
                binding.etOtp.text?.clear() // ready to type / paste again
            }
        }
    }

    private fun showOtpStep() {
        binding.otpSection.visibility = View.VISIBLE
        binding.tvFooterNote.visibility = View.GONE
        binding.btnSendOtp.visibility = View.GONE
        binding.etEmail.isEnabled = false
        binding.etOtp.requestFocus()
    }

    private fun backToEmailEntry() {
        stopCountdown()
        clearPending()
        binding.otpSection.visibility = View.GONE
        binding.tvFooterNote.visibility = View.VISIBLE
        binding.btnSendOtp.visibility = View.VISIBLE
        binding.btnSendOtp.isEnabled = true
        binding.etEmail.isEnabled = true
        binding.etOtp.text?.clear()
        binding.tvLoginStatus.text = ""
        binding.etEmail.requestFocus()
    }

    private fun startResendCountdown(seconds: Int) {
        stopCountdown()
        var left = seconds
        val runnable = object : Runnable {
            override fun run() {
                if (left > 0) {
                    binding.btnResendOtp.isEnabled = false
                    binding.btnResendOtp.alpha = 0.5f
                    binding.btnResendOtp.text = "Resend OTP in ${left}s"
                    left--
                    handler.postDelayed(this, 1000)
                } else {
                    binding.btnResendOtp.isEnabled = true
                    binding.btnResendOtp.alpha = 1f
                    binding.btnResendOtp.text = "Resend OTP"
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
        binding.btnResendOtp.text = "Resend OTP"
    }

    /** Shows a reassuring message if the backend hasn't responded within a few seconds. */
    private fun startSlowHint(message: String) {
        cancelSlowHint()
        val runnable = Runnable { binding.tvLoginStatus.text = message }
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

    companion object {
        /** Keep in sync with OTP_RESEND_COOLDOWN_SECONDS in backend/Code.gs. */
        private const val RESEND_SECONDS = 30
    }
}
