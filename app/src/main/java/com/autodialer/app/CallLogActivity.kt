package com.autodialer.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.autodialer.app.databinding.ActivityCallLogBinding
import java.text.SimpleDateFormat
import java.util.Locale

class CallLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallLogBinding
    private lateinit var store: CallLogStore
    private lateinit var adapter: CallLogAdapter
    private var days: List<String> = emptyList()
    private var selectedDay: String? = null
    private var lastClickMs = 0L

    private val storageDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("dd MMM yyyy, EEEE", Locale.getDefault())

    private fun formatSheetName(rawDay: String): String {
        return try {
            val date = storageDateFormat.parse(rawDay) ?: return rawDay
            displayDateFormat.format(date)
        } catch (e: Exception) {
            rawDay
        }
    }

    private var displayToStorageIndex: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CallLogStore(this)

        adapter = CallLogAdapter(
            mutableListOf(),
            OutcomeStore(this),
            onDelete = { position -> if (allowClick()) confirmDeleteRow(position) },
            onToggleCollected = { position -> if (allowClick(250L)) toggleCollected(position) },
            onCall = { position -> if (allowClick()) callFromSheet(position) }
        )
        binding.rvCallLog.layoutManager = LinearLayoutManager(this)
        binding.rvCallLog.adapter = adapter

        binding.btnDeleteSheet.setOnClickListener { if (allowClick()) confirmDeleteSheet() }
        binding.btnExportCsv.setOnClickListener { if (allowClick()) exportCurrentDayCsv() }

        loadDays()
    }

    private fun allowClick(debounceMs: Long = 450L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs < debounceMs) return false
        lastClickMs = now
        return true
    }

    private fun callFromSheet(displayPosition: Int) {
        val day = selectedDay ?: return
        val storageIndex = displayToStorageIndex.getOrNull(displayPosition) ?: return
        val entry = store.loadDay(day).getOrNull(storageIndex) ?: return
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

    private fun loadDays() {
        days = store.getDays().filter { it != store.todayKey() }.sortedDescending()
        if (days.isEmpty()) {
            binding.tvDaySummary.text = "No past sheets yet — today's calls appear on the Dashboard."
            binding.rvCallLog.visibility = android.view.View.GONE
            binding.tvEmptyState.visibility = android.view.View.VISIBLE
            binding.spinnerDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("(No past sheets)"))
            return
        }

        val labels = days.map { formatSheetName(it) }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerDay.adapter = spinnerAdapter
        binding.spinnerDay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedDay = days.getOrNull(position)
                loadEntriesForSelectedDay()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        selectedDay = days.first()
        loadEntriesForSelectedDay()
    }

    private fun outcomePriority(outcome: String?): Int = when (outcome) {
        Outcome.RESUME.id -> 0
        Outcome.POSITIVE.id -> 1
        Outcome.INFO.id -> 2
        Outcome.NO.id -> 3
        null -> 100
        else -> 50
    }

    private fun loadEntriesForSelectedDay() {
        val day = selectedDay ?: return
        val rawEntries = store.loadDay(day)

        val indexed = rawEntries.withIndex().toList()
        val sorted = indexed.sortedWith(compareBy({ outcomePriority(it.value.outcome) }, { it.value.time }))

        displayToStorageIndex = sorted.map { it.index }
        adapter.setEntries(sorted.map { it.value })

        binding.tvDaySummary.text = "${rawEntries.size} call(s) on ${formatSheetName(day)}"
        binding.rvCallLog.visibility = if (rawEntries.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvEmptyState.visibility = if (rawEntries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun toggleCollected(displayPosition: Int) {
        val day = selectedDay ?: return
        val storageIndex = displayToStorageIndex.getOrNull(displayPosition) ?: return
        store.toggleCollected(day, storageIndex)
        loadEntriesForSelectedDay()
    }

    private fun exportCurrentDayCsv() {
        val day = selectedDay ?: return
        val entries = store.loadDay(day)
        if (entries.isEmpty()) {
            Toast.makeText(this, "No call records available to export for this date.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val csv = StringBuilder()
            csv.append("Time,Name,Phone,Status,Outcome,Collected\n")
            entries.forEach { e ->
                val name = (e.name ?: "").replace(",", " ")
                val outcome = e.outcome ?: ""
                csv.append("${e.time},$name,${e.phone},${e.status},$outcome,${e.collected}\n")
            }

            val exportDir = java.io.File(cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(exportDir, "call_log_${day}.csv")
            file.writeText(csv.toString())

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share call log"))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to export CSV file.", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteRow(displayPosition: Int) {
        val day = selectedDay ?: return
        val storageIndex = displayToStorageIndex.getOrNull(displayPosition) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete call record?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteEntry(day, storageIndex)
                loadEntriesForSelectedDay()
                Toast.makeText(this, "Record deleted.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSheet() {
        val day = selectedDay ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete entire sheet?")
            .setMessage("The complete call log for ${formatSheetName(day)} will be permanently deleted.")
            .setPositiveButton("Delete Sheet") { _, _ ->
                store.deleteDay(day)
                Toast.makeText(this, "Sheet deleted.", Toast.LENGTH_SHORT).show()
                loadDays()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
