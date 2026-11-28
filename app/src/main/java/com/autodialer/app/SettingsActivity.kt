package com.autodialer.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import com.autodialer.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION = "settings_action"
        const val ACTION_EDIT_INFO_MESSAGE = "edit_info_message"
        const val ACTION_CUSTOM_OUTCOME = "custom_outcome"
        const val ACTION_BATCH_LIMIT = "batch_limit"
    }

    private lateinit var binding: ActivitySettingsBinding
    private var lastClickMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        setupRow(binding.rowSheets, "📋", "Sheets", "Numbers grouped by upload") {
            startActivity(Intent(this, CallLogActivity::class.java))
        }
        setupRow(binding.rowDashboard, "📊", "Dashboard", "Call history and outcomes") {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        setupRow(binding.rowPlan, "💳", "Plan / Subscription", SubscriptionManager(this).planSummaryForSettings()) {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }

        setupRow(binding.rowInfoMessage, "💬", "Edit Info Message", "Sent on WhatsApp for the Info outcome") {
            finishWithAction(ACTION_EDIT_INFO_MESSAGE)
        }
        setupRow(binding.rowCustomOutcome, "🏷️", "Custom Call Option", "Add your own outcome buttons") {
            finishWithAction(ACTION_CUSTOM_OUTCOME)
        }
        setupRow(binding.rowBatchLimit, "🔢", "Batch Limit", "Calls per session before pausing") {
            finishWithAction(ACTION_BATCH_LIMIT)
        }

        setupRow(binding.rowBattery, "🔋", "Keep App Running", "Stop the phone from closing this app mid-list") {
            requestIgnoreBatteryOptimizations()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.rowPlan.rowSubtitle.text = SubscriptionManager(this).planSummaryForSettings()
    }

    private fun allowClick(debounceMs: Long = 400L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs < debounceMs) return false
        lastClickMs = now
        return true
    }

    private fun setupRow(
        row: com.autodialer.app.databinding.RowSettingsItemBinding,
        icon: String,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        row.rowIcon.text = icon
        row.rowTitle.text = title
        row.rowSubtitle.text = subtitle
        row.root.setOnClickListener { if (allowClick()) onClick() }
    }

    private fun finishWithAction(action: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action))
        finish()
    }

    private fun requestIgnoreBatteryOptimizations() {
        BatteryExemptionFlow.openFromSettings(this)
    }
}
