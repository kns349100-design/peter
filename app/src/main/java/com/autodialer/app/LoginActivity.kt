package com.autodialer.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.autodialer.app.databinding.ActivityLoginBinding

/**
 * Login with email + OTP (mailed by the backend). A correct OTP makes this device
 * the only active device for that email.
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

        binding.btnSendOtp.setOnClickListener { requestOtp() }
        binding.btnResendOtp.setOnClickListener { requestOtp() }
        binding.btnVerifyOtp.setOnClickListener { verifyOtp() }
        binding.btnChangeEmail.setOnClickListener { backToEmailEntry() }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSlowHint()
        stopCountdown()
    }

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
        binding.tvLoginStatus.text = "Sending OTP..."
        startSlowHint("Still sending - the server can take a few extra seconds, hang on...")
        authManager.sendOtp(email) { success, message ->
            busy = false
            cancelSlowHint()
            binding.btnSendOtp.isEnabled = true
            if (success) {
                showOtpStep()
                binding.tvLoginStatus.text = "OTP sent to ${EmailValidator.mask(email)} - check your inbox (and spam)"
                startResendCountdown(60)
            } else {
                binding.tvLoginStatus.text = message
                // keep resend usable if we were already on the OTP step
                if (binding.otpSection.visibility == View.VISIBLE) binding.btnResendOtp.isEnabled = true
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
                authManager.setLoggedIn(email)
                goToMain()
            } else {
                binding.tvLoginStatus.text = message
            }
        }
    }

    private fun showOtpStep() {
        binding.otpSection.visibility = View.VISIBLE
        binding.btnSendOtp.visibility = View.GONE
        binding.etEmail.isEnabled = false
        binding.etOtp.requestFocus()
    }

    private fun backToEmailEntry() {
        stopCountdown()
        binding.otpSection.visibility = View.GONE
        binding.btnSendOtp.visibility = View.VISIBLE
        binding.etEmail.isEnabled = true
        binding.etOtp.text?.clear()
        binding.tvLoginStatus.text = ""
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

    /** Shows a reassuring message if the backend hasn't responded within a few seconds
     * (the free backend can be slow to "wake up" after being idle). */
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
}
