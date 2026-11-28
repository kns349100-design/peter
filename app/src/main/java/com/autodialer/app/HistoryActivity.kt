package com.autodialer.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.autodialer.app.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var store: CallLogStore
    private lateinit var outcomeStore: OutcomeStore
    private lateinit var adapter: CallLogAdapter
    private var displayToStorageIndex: List<Int> = emptyList()
    private var lastClickMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CallLogStore(this)
        outcomeStore = OutcomeStore(this)

        adapter = CallLogAdapter(
            mutableListOf(),
            outcomeStore,
            onDelete = { position -> if (allowClick()) confirmDeleteRow(position) },
            onToggleCollected = { position -> if (allowClick(250L)) toggleCollected(position) },
            onCall = { position -> if (allowClick()) callFromDashboard(position) }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        loadToday()
    }

    private fun allowClick(debounceMs: Long = 450L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs < debounceMs) return false
        lastClickMs = now
        return true
    }

    private fun callFromDashboard(displayPosition: Int) {
        val storageIndex = displayToStorageIndex.getOrNull(displayPosition) ?: return
        val entry = store.loadDay(store.todayKey()).getOrNull(storageIndex) ?: return
        val phone = entry.phone.trim()
        if (!PhoneUtils.isValidDialableNumber(phone)) {
            Toast.makeText(this, "Invalid phone number.", Toast.LENGTH_SHORT).show()
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val intent = if (hasPermission) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
        } else {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to place the call.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadToday()
    }

    private fun loadToday() {
        val today = store.todayKey()
        val entries = store.loadDay(today)

        val completedOrSkipped = entries.filter { it.status == "Completed" || it.status == "Skipped" }
        val untagged = completedOrSkipped.count { it.outcome == null }
        val perOutcomeSummary = outcomeStore.allOutcomes()
            .map { o -> o.label to completedOrSkipped.count { it.outcome == o.id } }
            .filter { it.second > 0 }
            .joinToString("   ") { "${it.first}: ${it.second}" }

        val todayLabel = SimpleDateFormat("dd MMM yyyy, EEEE", Locale.getDefault()).format(Date())
        binding.tvHistorySummary.text =
            "Today — $todayLabel\nTotal calls: ${entries.size}   $perOutcomeSummary   Untagged: $untagged"

        val indexed = entries.withIndex().toList()
        val sorted = indexed.sortedByDescending { it.value.time }
        displayToStorageIndex = sorted.map { it.index }
        adapter.setEntries(sorted.map { it.value })
    }

    private fun toggleCollected(displayPosition: Int) {
        val storageIndex = displayToStorageIndex.getOrNull(displayPosition) ?: return
        store.toggleCollected(store.todayKey(), storageIndex)
        loadToday()
    }

    private fun confirmDeleteRow(displayPosition: Int) {
        val storageIndex = displayToStorageIndex.getOrNull(displayPosition) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete call record?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteEntry(store.todayKey(), storageIndex)
                loadToday()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
