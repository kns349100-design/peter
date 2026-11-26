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
    private lateinit var securePrefs: SecurePrefs
    private val handler = Handler(Looper.getMainLooper())
    private var slowHintRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var resendEndTimeMs: Long = 0L
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(this)
        securePrefs = SecurePrefs(this)

        if (authManager.isLoggedIn()) {
            goToMain()
            return
        }

        tap(binding.btnSendOtp) { requestOtp() }
        tap(binding.btnResendOtp) { requestOtp() }
        tap(binding.btnVerifyOtp) { verifyOtp() }
        tap(binding.btnChangeEmail) { backToEmailEntry() }

        // Keyboard "Send" / "Done" key on the email field triggers Send OTP
        binding.etEmail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                requestOtp()
                true
            } else {
                false
            }
        }

        // Auto-verify as soon as 6 digits are entered
        binding.etOtp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val digitCount = s?.count { it.isDigit() } ?: 0
                if (digitCount == 6 && !busy) {
                    verifyOtp()
                }
            }
        })

        restorePendingOtpStep()
    }

    override fun onDestroy() {
        cancelSlowHint()
        stopCountdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            pasteFreshOtpFromClipboard()
        }
    }

    // ---------------------------------------------------------------- touch feedback

    /** Click handler + visible press animation + vibration tick. */
    private fun tap(view: View, action: () -> Unit) {
        view.isClickable = true
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .alpha(0.75f)
                        .setDuration(70)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(120)
                        .start()
                }
            }
            false
        }
        view.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
    }

    // ---------------------------------------------------------------- state restore

    private fun savePending(email: String) {
        securePrefs.putString(KEY_PENDING_EMAIL, email)
        securePrefs.putLong(KEY_PENDING_SENT_AT, System.currentTimeMillis())
    }

    private fun clearPending() {
        securePrefs.remove(KEY_PENDING_EMAIL)
        securePrefs.remove(KEY_PENDING_SENT_AT)
    }

    /** If an OTP was sent within the last 10 minutes, reopen straight on the OTP step. */
    private fun restorePendingOtpStep() {
        val email = securePrefs.getString(KEY_PENDING_EMAIL, null) ?: return
        val sentAt = securePrefs.getLong(KEY_PENDING_SENT_AT, 0L)
        val ageMs = System.currentTimeMillis() - sentAt
        if (sentAt <= 0L || ageMs !in 0..OTP_VALIDITY_MS) {
            clearPending()
            return
        }

        binding.etEmail.setText(email)
        showOtpStep()
        binding.tvLoginStatus.text = "OTP sent to ${EmailValidator.mask(email)} - enter it below"

        val elapsedSec = (ageMs / 1000L).toInt()
        val leftSec = RESEND_SECONDS - elapsedSec
        if (leftSec > 0) {
            startResendCountdown(leftSec)
        } else {
            stopCountdown()
            binding.btnResendOtp.isEnabled = true
        }
    }

    /** If the user recently copied the 6-digit code from email, fill it in automatically. */
    private fun pasteFreshOtpFromClipboard() {
        if (isFinishing || isDestroyed) return
        if (binding.otpSection.visibility != View.VISIBLE || busy) return
        if (!binding.etOtp.text.isNullOrEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return

            val copiedAt = clip.description?.timestamp ?: 0L
            val sentAt = securePrefs.getLong(KEY_PENDING_SENT_AT, 0L)
            if (sentAt > 0L && copiedAt > 0L && copiedAt < sentAt) return

            val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
            val code = SIX_DIGIT_REGEX.find(text)?.value ?: return
            binding.etOtp.setText(code) // Triggers TextWatcher -> verifyOtp()
        } catch (_: Exception) {
            // Ignore clipboard access errors on restricted OEM builds
        }
    }

    // ---------------------------------------------------------------- actions

    private fun validEmail(): String? {
        val email = EmailValidator.normalize(binding.etEmail.text?.toString().orEmpty())
        if (email == null) {
            binding.tvLoginStatus.text = "Enter a valid email address"
        }
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

        // Open the OTP step immediately so the user isn't stuck waiting on a slow backend
        showOtpStep()
        binding.tvLoginStatus.text = "Sending OTP to ${EmailValidator.mask(email)}..."
        startSlowHint("Still sending - the server can take a few extra seconds, hang on...")

        authManager.sendOtp(email) { success, message ->
            if (!isFinishing && !isDestroyed) {
                busy = false
                cancelSlowHint()

                if (success) {
                    savePending(email)
                    binding.tvLoginStatus.text =
                        "OTP sent to ${EmailValidator.mask(email)} - check inbox (and spam)"
                    startResendCountdown(RESEND_SECONDS)
                    pasteFreshOtpFromClipboard()
                } else {
                    stopCountdown()
                    binding.btnResendOtp.isEnabled = true
                    binding.tvLoginStatus.text = message
                }
            }
        }
    }

    private fun verifyOtp() {
        if (busy) return
        val email = validEmail() ?: return
        val otp = binding.etOtp.text?.toString()?.filter { it.isDigit() }.orEmpty()
        if (otp.length != 6) {
            binding.tvLoginStatus.text = "Enter the 6-digit OTP"
            return
        }

        busy = true
        binding.btnVerifyOtp.isEnabled = false
        binding.tvLoginStatus.text = "Verifying..."
        startSlowHint("Still verifying - the server can take a few extra seconds, hang on...")

        authManager.verifyOtp(email, otp) { success, message ->
            if (!isFinishing && !isDestroyed) {
                busy = false
                cancelSlowHint()
                binding.btnVerifyOtp.isEnabled = true

                if (success) {
                    clearPending()
                    authManager.setLoggedIn(email)
                    goToMain()
                } else {
                    binding.tvLoginStatus.text = message
                    binding.etOtp.text?.clear()
                }
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
        resendEndTimeMs = System.currentTimeMillis() + seconds.coerceAtLeast(0) * 1000L

        val runnable = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val remainingMs = resendEndTimeMs - System.currentTimeMillis()
                val leftSec = ((remainingMs + 999L) / 1000L).toInt()
                if (leftSec > 0) {
                    binding.btnResendOtp.isEnabled = false
                    binding.btnResendOtp.alpha = 0.5f
                    binding.btnResendOtp.text = "Resend OTP in ${leftSec}s"
                    handler.postDelayed(this, 250L)
                } else {
                    binding.btnResendOtp.isEnabled = true
                    binding.btnResendOtp.alpha = 1f
                    binding.btnResendOtp.text = "Resend OTP"
                    countdownRunnable = null
                }
            }
        }
        countdownRunnable = runnable
        handler.post(runnable)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        resendEndTimeMs = 0L
        binding.btnResendOtp.alpha = 1f
        binding.btnResendOtp.text = "Resend OTP"
    }

    /** Shows a reassuring message if the backend hasn't responded within a few seconds. */
    private fun startSlowHint(message: String) {
        cancelSlowHint()
        val runnable = Runnable {
            if (!isFinishing && !isDestroyed) {
                binding.tvLoginStatus.text = message
            }
        }
        slowHintRunnable = runnable
        handler.postDelayed(runnable, SLOW_HINT_DELAY_MS)
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
        private const val OTP_VALIDITY_MS = 10 * 60 * 1000L
        private const val SLOW_HINT_DELAY_MS = 4000L
        private const val KEY_PENDING_EMAIL = "login_pending_email"
        private const val KEY_PENDING_SENT_AT = "login_pending_sent_at"
        private val SIX_DIGIT_REGEX = Regex("\\b\\d{6}\\b")
    }
}
