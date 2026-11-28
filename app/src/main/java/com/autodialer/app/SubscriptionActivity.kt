package com.autodialer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autodialer.app.databinding.ActivitySubscriptionBinding
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import org.json.JSONObject

class SubscriptionActivity : AppCompatActivity(), PaymentResultWithDataListener {

    private lateinit var binding: ActivitySubscriptionBinding
    private lateinit var subscriptionManager: SubscriptionManager
    private var pendingPlanType: String? = null
    private var pendingOrderId: String? = null
    private var pendingAmountRupees: Int = 0
    private var lastPaymentId: String? = null
    private var checkoutOpen = false
    private var confirming = false
    private var lastClickMs = 0L
    private val slowHintHandler = Handler(Looper.getMainLooper())
    private var slowHintRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        subscriptionManager = SubscriptionManager(this)
        subscriptionManager.ensureFirstLaunchRecorded()
        subscriptionManager.refreshStatusInBackground()
        Checkout.preload(applicationContext)
        restorePendingPaymentIfAny()

        binding.btnFreeTrial.setOnClickListener {
            if (!allowClick()) return@setOnClickListener
            binding.btnFreeTrial.isEnabled = false
            val email = AuthManager(this).email()
            subscriptionManager.startFreeTrial(email) { success, message ->
                binding.btnFreeTrial.isEnabled = true
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshUi()
                if (success) goToMainAfterDelay()
            }
        }
        binding.btnPay12Hour.setOnClickListener {
            if (allowClick()) startRazorpayPayment("HOURLY12", 9, "12 Hour Access")
        }
        binding.btnPayMonthly.setOnClickListener {
            if (allowClick()) startRazorpayPayment("MONTHLY", 299, "1 Month Access")
        }
        binding.btnPayYearly.setOnClickListener {
            if (allowClick()) startRazorpayPayment("YEARLY", 999, "1 Year Access")
        }
        binding.btnCheckPayment.setOnClickListener {
            if (allowClick()) sendPaymentProofOnWhatsApp()
        }

        binding.btnRedeem.setOnClickListener {
            if (!allowClick()) return@setOnClickListener
            val code = binding.etCode.text.toString()
            if (code.isBlank()) {
                binding.tvRedeemResult.text = "Enter a promo code."
                return@setOnClickListener
            }
            binding.btnRedeem.isEnabled = false
            binding.tvRedeemResult.text = "Verifying code..."
            startSlowHint(binding.tvRedeemResult, "Still verifying — the server is waking up, please wait...")
            subscriptionManager.redeemCode(code) { success, message, planType ->
                cancelSlowHint()
                binding.btnRedeem.isEnabled = true
                binding.tvRedeemResult.text = message
                if (success) {
                    refreshUi()
                    if (!planType.isNullOrBlank()) {
                        showCongratulationsDialog(planType, null)
                    } else {
                        goToMainAfterDelay()
                    }
                }
            }
        }

        refreshUi()
    }

    private fun allowClick(debounceMs: Long = 500L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs < debounceMs) return false
        lastClickMs = now
        return true
    }

    private fun startRazorpayPayment(planType: String, amountRupees: Int, planLabel: String) {
        if (checkoutOpen || confirming) return
        setPayButtonsEnabled(false)
        binding.tvPaymentResult.text = "Starting secure payment..."
        subscriptionManager.createRazorpayOrder(planType) { order, message ->
            if (order == null) {
                setPayButtonsEnabled(true)
                binding.tvPaymentResult.text = message
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return@createRazorpayOrder
            }
            pendingPlanType = planType
            pendingAmountRupees = amountRupees
            pendingOrderId = order.orderId
            lastPaymentId = null
            subscriptionManager.savePendingPayment(planType, amountRupees, System.currentTimeMillis(), order.orderId)
            openCheckout(order, planLabel)
        }
    }

    private fun openCheckout(order: SubscriptionManager.RazorpayOrder, planLabel: String) {
        try {
            val checkout = Checkout()
            checkout.setKeyID(order.keyId)
            val email = AuthManager(this).email()
            val options = JSONObject()
            options.put("name", "CallConnect")
            options.put("description", planLabel)
            options.put("order_id", order.orderId)
            options.put("currency", "INR")
            options.put("amount", order.amountPaise)
            if (email.isNotBlank()) {
                options.put("prefill", JSONObject().put("email", email))
            }
            options.put("theme", JSONObject().put("color", "#1A56DB"))
            checkoutOpen = true
            binding.tvPaymentResult.text = "Complete the payment in the checkout window."
            binding.btnCheckPayment.visibility = android.view.View.VISIBLE
            checkout.open(this, options)
        } catch (e: Exception) {
            checkoutOpen = false
            setPayButtonsEnabled(true)
            Toast.makeText(this, "Unable to open payment checkout.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        checkoutOpen = false
        lastPaymentId = razorpayPaymentId
        confirmPendingPayment(quiet = false)
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        checkoutOpen = false
        setPayButtonsEnabled(true)
        binding.tvPaymentResult.text = "Payment cancelled or failed. If money was deducted, it will be activated automatically — or contact support via WhatsApp below."
        binding.btnCheckPayment.visibility = android.view.View.VISIBLE
    }

    private fun confirmPendingPayment(quiet: Boolean, attempt: Int = 0) {
        val orderId = pendingOrderId ?: return
        if (confirming) return
        confirming = true
        if (!quiet) {
            binding.tvPaymentResult.text = "Confirming your payment..."
            startSlowHint(binding.tvPaymentResult, "Still confirming — the server is waking up, please wait...")
        }
        subscriptionManager.confirmRazorpayPayment(orderId) { success, planType, message ->
            cancelSlowHint()
            val retryable = !success && !quiet && attempt < 3 &&
                !message.contains("another device", ignoreCase = true)
            if (retryable) {
                binding.tvPaymentResult.text = "Confirming your payment (attempt ${attempt + 2} of 4)..."
                slowHintHandler.postDelayed({
                    confirming = false
                    if (!isFinishing && !isDestroyed) confirmPendingPayment(quiet = false, attempt = attempt + 1)
                }, 2000L * (attempt + 1))
                return@confirmRazorpayPayment
            }
            confirming = false
            setPayButtonsEnabled(true)
            if (success && !planType.isNullOrBlank()) {
                val amount = if (pendingAmountRupees > 0) pendingAmountRupees else null
                subscriptionManager.clearPendingPayment()
                pendingOrderId = null
                pendingPlanType = null
                binding.btnCheckPayment.visibility = android.view.View.GONE
                binding.tvPaymentResult.text = "Payment confirmed."
                refreshUi()
                showCongratulationsDialog(planType, amount)
            } else {
                binding.tvPaymentResult.text = if (quiet) {
                    "Last payment is still pending confirmation. If money was deducted, it will activate automatically — or contact support via WhatsApp below."
                } else {
                    "$message\nIf money was deducted, it will activate automatically — or contact support via WhatsApp below."
                }
                binding.btnCheckPayment.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun setPayButtonsEnabled(enabled: Boolean) {
        binding.btnPay12Hour.isEnabled = enabled
        binding.btnPayMonthly.isEnabled = enabled
        binding.btnPayYearly.isEnabled = enabled
    }

    private fun restorePendingPaymentIfAny() {
        val pending = subscriptionManager.loadPendingPayment() ?: return
        if (!pending.reference.startsWith("order_")) {
            subscriptionManager.clearPendingPayment()
            return
        }
        pendingPlanType = pending.planType
        pendingAmountRupees = pending.amountRupees
        pendingOrderId = pending.reference
        binding.tvPaymentResult.text = "Checking your recent payment..."
        binding.btnCheckPayment.visibility = android.view.View.VISIBLE
    }

    private fun showCongratulationsDialog(planType: String, amountRupees: Int?) {
        val (planName, priceLabel) = subscriptionManager.planDisplayInfo(planType)
        val expiryLabel = subscriptionManager.expiryDateLabel()

        val dialogView = layoutInflater.inflate(R.layout.dialog_congrats, null)
        dialogView.findViewById<android.widget.TextView>(R.id.tvCongratsPlanName).text = planName
        dialogView.findViewById<android.widget.TextView>(R.id.tvCongratsBadge).text = planType
        dialogView.findViewById<android.widget.TextView>(R.id.tvCongratsExpiry).text = expiryLabel

        val amountView = dialogView.findViewById<android.widget.TextView>(R.id.tvCongratsAmount)
        if (amountRupees != null) {
            amountView.text = "₹$amountRupees paid • $priceLabel"
            amountView.visibility = android.view.View.VISIBLE
        } else {
            amountView.visibility = android.view.View.GONE
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<android.widget.TextView>(R.id.btnCongratsStart).setOnClickListener {
            dialog.dismiss()
            goToMainAfterDelay(0)
        }
        dialog.show()
    }

    private fun sendPaymentProofOnWhatsApp() {
        val message = "Hi, I've paid for CallConnect. Order: ${pendingOrderId ?: "-"}, Payment: ${lastPaymentId ?: "-"}. Please activate my plan."
        val encodedMessage = java.net.URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("https://wa.me/919075034748?text=$encodedMessage")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp is not installed on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goToMainAfterDelay(delayMs: Long = 1200) {
        slowHintHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }, delayMs)
    }

    private fun startSlowHint(target: android.widget.TextView, message: String) {
        cancelSlowHint()
        val runnable = Runnable { target.text = message }
        slowHintRunnable = runnable
        slowHintHandler.postDelayed(runnable, 4000)
    }

    private fun cancelSlowHint() {
        slowHintRunnable?.let { slowHintHandler.removeCallbacks(it) }
        slowHintRunnable = null
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        if (!checkoutOpen && !confirming && pendingOrderId != null) {
            confirmPendingPayment(quiet = true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSlowHint()
    }

    private fun refreshUi() {
        binding.tvPlanStatus.text = subscriptionManager.currentPlanLabel()
        binding.tvTrialInfo.text = when {
            subscriptionManager.isSubscribed() -> "Plan active — ${subscriptionManager.remainingTimeLabel()}"
            subscriptionManager.isTrialActive() -> {
                val hoursLeft = subscriptionManager.trialMillisRemaining() / (1000 * 60 * 60)
                "${hoursLeft}h left in trial"
            }
            subscriptionManager.hasStartedFreeTrial() -> "Trial expired — subscribe to continue"
            else -> "Select a plan below to get started"
        }
        binding.btnFreeTrial.visibility =
            if (subscriptionManager.hasStartedFreeTrial()) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvDeviceId.text = "Device ID: ${subscriptionManager.deviceId()}"
    }
}
