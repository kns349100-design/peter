package com.autodialer.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.autodialer.app.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : AppCompatActivity(), CallEngineListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: NumberAdapter
    private lateinit var sessionStore: SessionStore
    private lateinit var callLogStore: CallLogStore
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var outcomeStore: OutcomeStore
    private lateinit var engine: CallEngine
    private lateinit var infoMessageStore: InfoMessageStore
    private lateinit var authManager: AuthManager
    private lateinit var numberDraftStore: NumberDraftStore

    private var pendingInfoOutcome = false
    private var isLoadingList = false
    private var lastClickElapsedMs = 0L

    private val draftHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val draftSaveRunnable = Runnable { saveDraftNow() }

    private fun saveDraftNow() {
        if (!::binding.isInitialized || !::numberDraftStore.isInitialized) return
        numberDraftStore.setDraft(binding.etNumbers.text.toString())
    }

    private var batchTargetPref = 0

    private val requiredPermissions = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE
    )
    private val permissionRequestCode = 101

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { importNumbersFromFile(it) }
            }
        }

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val uris = mutableListOf<Uri>()
                val clipData = data?.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        uris.add(clipData.getItemAt(i).uri)
                    }
                } else {
                    data?.data?.let { uris.add(it) }
                }
                if (uris.isNotEmpty()) extractNumbersFromImages(uris)
            }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                when (result.data?.getStringExtra(SettingsActivity.EXTRA_ACTION)) {
                    SettingsActivity.ACTION_EDIT_INFO_MESSAGE -> showEditInfoMessageDialog()
                    SettingsActivity.ACTION_CUSTOM_OUTCOME -> showManageOutcomesDialog()
                    SettingsActivity.ACTION_BATCH_LIMIT -> showBatchLimitDialog()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authManager = AuthManager(this)
        if (!authManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStore = SessionStore(this)
        callLogStore = CallLogStore(this)
        subscriptionManager = SubscriptionManager(this)
        outcomeStore = OutcomeStore(this)
        infoMessageStore = InfoMessageStore(this)
        numberDraftStore = NumberDraftStore(this)
        subscriptionManager.ensureFirstLaunchRecorded()
        subscriptionManager.refreshStatusInBackground()

        if (!subscriptionManager.hasAccess()) {
            startActivity(Intent(this, SubscriptionActivity::class.java))
            finish()
            return
        }

        engine = CallEngine(this, sessionStore, callLogStore, this, AndroidKeepAlive.get(this))
        requestNotificationPermissionOnce()
        Thread {
            FileStore.encryptLegacyFiles(applicationContext)
            FileStore.purgeStaleExports(applicationContext)
        }.also { it.isDaemon = true }.start()

        adapter = NumberAdapter(mutableListOf(), outcomeStore)
        binding.rvNumbers.layoutManager = LinearLayoutManager(this)
        binding.rvNumbers.adapter = adapter

        requestNeededPermissions()

        binding.btnPasteClipboard.setOnClickListener { if (allowClick()) pasteFromClipboard() }
        binding.btnImportFile.setOnClickListener { if (allowClick()) openFilePicker() }
        binding.btnImportImage.setOnClickListener { if (allowClick()) openImagePicker() }
        binding.btnLoadListTop.setOnClickListener { if (allowClick()) loadNumbersFromInput() }
        binding.btnStart.setOnClickListener { if (allowClick()) onStartClicked() }
        binding.btnStop.setOnClickListener { if (allowClick()) engine.stop() }
        binding.btnFloatingStop.setOnClickListener { if (allowClick()) engine.stop() }
        binding.btnSettings.setOnClickListener {
            if (allowClick()) settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        binding.btnRemoveList.setOnClickListener { if (allowClick()) confirmRemoveList() }

        setLoadButtonActive(false)
        binding.etNumbers.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                setLoadButtonActive(false)
                draftHandler.removeCallbacks(draftSaveRunnable)
                draftHandler.postDelayed(draftSaveRunnable, 500)
            }
        })

        val draft = numberDraftStore.getDraft()
        if (draft.isNotBlank() && binding.etNumbers.text.isBlank()) {
            binding.etNumbers.setText(draft)
        }

        renderOutcomeOverlay()

        if (sessionStore.hasSavedSession() && engine.restoreIfAvailable()) {
            adapter.setLeads(engine.leads)
            refreshDashboard()
            val statusText = engine.restoreSummary ?: "in progress"
            showToast("Session restored ($statusText). Tap Start to resume.", Toast.LENGTH_LONG)
            if (engine.isAwaitingWhatsAppReturn()) {
                pendingInfoOutcome = true
            } else if (engine.pendingLead() != null) {
                hideKeyboard()
                binding.overlayOutcome.visibility = View.VISIBLE
            }
        }
    }

    private fun allowClick(debounceMs: Long = 450L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickElapsedMs < debounceMs) return false
        lastClickElapsedMs = now
        return true
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        if (!isFinishing && !isDestroyed) {
            Toast.makeText(this, message, duration).show()
        }
    }

    private fun requestNeededPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequestCode)
        }
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions required")
            .setMessage("Phone and Call State permissions are required to place and track calls automatically. Enable them in App Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else {
            ""
        }
        if (text.isBlank()) {
            showToast("Clipboard is empty.")
        } else {
            val existing = binding.etNumbers.text.toString()
            binding.etNumbers.setText(if (existing.isBlank()) text else "$existing\n$text")
            showToast("Numbers pasted from clipboard.")
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/comma-separated-values", "text/csv"))
        }
        filePickerLauncher.launch(intent)
    }

    private fun importNumbersFromFile(uri: Uri) {
        Thread {
            try {
                val content = contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                }
                runOnUiThread {
                    if (content == null) {
                        showToast("Unable to read the selected file.", Toast.LENGTH_LONG)
                        return@runOnUiThread
                    }
                    if (isFinishing || isDestroyed) {
                        appendToDraftStore(content)
                        return@runOnUiThread
                    }
                    val existing = binding.etNumbers.text.toString()
                    val merged = if (existing.isBlank()) content else "$existing\n$content"
                    binding.etNumbers.setText(merged)
                    showToast("File imported. Tap Load to prepare the call list.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showToast("Failed to import file. Please try another CSV or TXT file.", Toast.LENGTH_LONG)
                }
            }
        }.start()
    }

    private fun appendToDraftStore(newText: String) {
        val store = NumberDraftStore(applicationContext)
        val existing = store.getDraft()
        store.setDraft(if (existing.isBlank()) newText else "$existing\n$newText")
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        imagePickerLauncher.launch(Intent.createChooser(intent, "Select images containing numbers"))
    }

    private fun extractNumbersFromImages(uris: List<Uri>) {
        showToast("Scanning ${uris.size} image(s)...")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val allNumbers = linkedSetOf<String>()
        val failedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val remaining = java.util.concurrent.atomic.AtomicInteger(uris.size)

        fun oneDone() {
            if (remaining.decrementAndGet() == 0) {
                try {
                    recognizer.close()
                } catch (_: Exception) {
                }
                val snapshot = synchronized(allNumbers) { LinkedHashSet(allNumbers) }
                onImagesProcessed(snapshot, failedCount.get())
            }
        }

        Thread {
            uris.forEach { uri ->
                try {
                    val bitmap = decodeSampledBitmap(uri, maxDimension = 2048)
                    if (bitmap == null) {
                        failedCount.incrementAndGet()
                        oneDone()
                        return@forEach
                    }
                    val image = InputImage.fromBitmap(bitmap, 0)
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            synchronized(allNumbers) {
                                allNumbers.addAll(NumberParser.extractPhoneNumbers(visionText.text))
                            }
                            oneDone()
                        }
                        .addOnFailureListener {
                            failedCount.incrementAndGet()
                            oneDone()
                        }
                } catch (_: Exception) {
                    failedCount.incrementAndGet()
                    oneDone()
                }
            }
        }.start()
    }

    private fun decodeSampledBitmap(uri: Uri, maxDimension: Int): android.graphics.Bitmap? {
        return try {
            val boundsOptions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, boundsOptions)
            }
            var sampleSize = 1
            val (w, h) = boundsOptions.outWidth to boundsOptions.outHeight
            if (w > 0 && h > 0) {
                while ((w / sampleSize) > maxDimension || (h / sampleSize) > maxDimension) {
                    sampleSize *= 2
                }
            }
            val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun onImagesProcessed(numbers: Set<String>, failedCount: Int) {
        runOnUiThread {
            val screenGone = isFinishing || isDestroyed
            if (numbers.isEmpty()) {
                if (screenGone) return@runOnUiThread
                val msg = if (failedCount > 0) {
                    "Could not read $failedCount image(s). Please use clear, uncropped screenshots."
                } else {
                    "No valid phone numbers found in the selected image(s)."
                }
                showToast(msg, Toast.LENGTH_LONG)
                return@runOnUiThread
            }
            val newText = numbers.joinToString("\n")
            if (screenGone) {
                appendToDraftStore(newText)
                return@runOnUiThread
            }
            val existing = binding.etNumbers.text.toString()
            val merged = if (existing.isBlank()) newText else "$existing\n$newText"
            binding.etNumbers.setText(merged)
            val summary = buildString {
                append("${numbers.size} valid number(s) extracted.")
                if (failedCount > 0) append(" $failedCount image(s) skipped.")
                append(" Tap Load to prepare the list.")
            }
            showToast(summary, Toast.LENGTH_LONG)
        }
    }

    private fun setLoadButtonActive(active: Boolean) {
        val drawableRes = if (active) R.drawable.bg_pill_load_active else R.drawable.bg_pill_load_idle
        binding.btnLoadListTop.background = ContextCompat.getDrawable(this, drawableRes)
    }

    private fun showBatchLimitDialog() {
        val editText = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(if (batchTargetPref > 0) batchTargetPref.toString() else "")
            hint = "0 = unlimited (dial entire list)"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Batch call limit")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                batchTargetPref = editText.text.toString().trim().toIntOrNull() ?: 0
                showToast("Batch limit updated.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadNumbersFromInput() {
        if (isLoadingList) return
        if (engine.hasActiveList()) {
            showToast("An active list is already loaded. Complete or remove it before loading a new one.", Toast.LENGTH_LONG)
            return
        }
        val raw = binding.etNumbers.text.toString()
        if (raw.isBlank()) {
            showToast("Paste or import phone numbers first.")
            return
        }

        isLoadingList = true
        binding.btnLoadListTop.isEnabled = false

        Thread {
            try {
                val rawLines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val parsedLeads = mutableListOf<Lead>()
                var invalidLineCount = 0
                var runningIndex = 1

                for (line in rawLines) {
                    val leadsFromLine = NumberParser.extractLeadsFromLine(line, runningIndex)
                    if (leadsFromLine.isEmpty()) {
                        invalidLineCount++
                    } else {
                        parsedLeads.addAll(leadsFromLine)
                        runningIndex += leadsFromLine.size
                    }
                }

                val seen = mutableSetOf<String>()
                var duplicateCount = 0
                val deduped = mutableListOf<Lead>()
                for (lead in parsedLeads) {
                    val normalized = PhoneUtils.normalize(lead.phone)
                    if (seen.add(normalized)) {
                        deduped.add(lead)
                    } else {
                        duplicateCount++
                    }
                }

                val alreadyCalledPhones = callLogStore.allCalledPhones()
                val neverCalled = deduped.filter { !alreadyCalledPhones.contains(PhoneUtils.normalize(it.phone)) }
                val alreadyCalledCount = deduped.size - neverCalled.size

                val warnings = mutableListOf<String>()
                if (duplicateCount > 0) warnings.add("$duplicateCount duplicate number(s) removed.")
                if (invalidLineCount > 0) warnings.add("$invalidLineCount invalid or incomplete entry(s) skipped.")
                if (alreadyCalledCount > 0) warnings.add("$alreadyCalledCount previously called number(s) excluded.")

                runOnUiThread {
                    isLoadingList = false
                    binding.btnLoadListTop.isEnabled = true
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    if (parsedLeads.isEmpty()) {
                        binding.tvDuplicateInfo.text = if (invalidLineCount > 0) {
                            "$invalidLineCount invalid entry(s) skipped. Enter valid 10-digit numbers."
                        } else ""
                        showToast("No valid phone numbers found.")
                        return@runOnUiThread
                    }

                    binding.tvDuplicateInfo.text = warnings.joinToString("\n")

                    if (neverCalled.isEmpty()) {
                        showToast("All valid numbers in this input have already been called.", Toast.LENGTH_LONG)
                        return@runOnUiThread
                    }

                    AlertDialog.Builder(this)
                        .setTitle("Confirm contact list")
                        .setMessage("Load ${neverCalled.size} verified contact(s) into the dialer queue?")
                        .setPositiveButton("Load List") { _, _ ->
                            engine.loadLeads(neverCalled, "Session ${System.currentTimeMillis()}")
                            adapter.setLeads(engine.leads)
                            refreshDashboard()
                            setLoadButtonActive(true)
                            binding.etNumbers.setText("")
                            showToast("${neverCalled.size} contact(s) loaded.")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    isLoadingList = false
                    binding.btnLoadListTop.isEnabled = true
                    showToast("Unable to process the number list. Please check the format.")
                }
            }
        }.start()
    }

    private fun onStartClicked() {
        hideKeyboard()
        if (!subscriptionManager.hasAccess()) {
            showToast("Your plan has expired. Please activate a plan to continue.", Toast.LENGTH_LONG)
            startActivity(Intent(this, SubscriptionActivity::class.java))
            return
        }
        if (engine.leads.isEmpty()) {
            showToast("Load a contact list first.")
            return
        }
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            val permPrefs = SecurePrefs.open(this, "perm_prefs")
            val permanentlyDenied = missingPermissions.any {
                !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
            } && permPrefs.getBoolean("asked_once", false)

            if (permanentlyDenied) {
                showPermissionSettingsDialog()
            } else {
                permPrefs.edit().putBoolean("asked_once", true).apply()
                requestNeededPermissions()
                showToast("Phone and Call State permissions are required.")
            }
            return
        }
        engine.batchTarget = batchTargetPref
        BatteryExemptionFlow.gateThen(this) { engine.start() }
    }

    private fun confirmRemoveList() {
        AlertDialog.Builder(this)
            .setTitle("Clear active list?")
            .setMessage("Completed calls remain saved in Call Sheets. Remaining pending numbers will be cleared.")
            .setPositiveButton("Clear List") { _, _ ->
                engine.removeCurrentList()
                clearListUi()
                showToast("Active list cleared.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearListUi() {
        binding.etNumbers.setText("")
        binding.tvDuplicateInfo.text = ""
        adapter.setLeads(engine.leads)
        binding.overlayOutcome.visibility = View.GONE
        binding.btnFloatingStop.visibility = View.GONE
        setLoadButtonActive(false)
        refreshDashboard()
    }

    private fun selectOutcomeAndHide(outcomeId: String) {
        binding.overlayOutcome.visibility = View.GONE
        if (outcomeId == Outcome.INFO.id) {
            val lead = engine.pendingLead()
            if (lead != null) {
                pendingInfoOutcome = true
                engine.markAwaitingWhatsAppReturn()
                openWhatsAppWithMessage(lead.phone)
            } else {
                engine.selectOutcome(outcomeId)
            }
        } else {
            engine.selectOutcome(outcomeId)
        }
    }

    private fun openWhatsAppWithMessage(phone: String) {
        val withCountryCode = PhoneUtils.toWhatsAppDigits(phone)
        val message = infoMessageStore.getMessage()
        val encodedMessage = java.net.URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("https://wa.me/$withCountryCode?text=$encodedMessage")

        for (pkg in listOf("com.whatsapp.w4b", "com.whatsapp")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage(pkg) }
                startActivity(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: android.content.ActivityNotFoundException) {
            pendingInfoOutcome = false
            engine.selectOutcome(Outcome.INFO.id)
            showToast("WhatsApp is not installed on this device.")
        }
    }

    private fun showEditInfoMessageDialog() {
        val editText = android.widget.EditText(this).apply {
            setText(infoMessageStore.getMessage())
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("WhatsApp follow-up message")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                infoMessageStore.setMessage(editText.text.toString())
                showToast("Message saved.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderOutcomeOverlay() {
        val container = binding.outcomeButtonsContainer
        container.removeAllViews()
        val outcomes = outcomeStore.allOutcomes()

        outcomes.chunked(2).forEach { rowOutcomes ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
            }
            rowOutcomes.forEachIndexed { i, outcome ->
                val button = android.widget.TextView(this).apply {
                    text = outcome.label.uppercase()
                    setTextColor(outcome.textColor())
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(outcome.color())
                    }
                    background = bg
                    elevation = dp(16).toFloat()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        outlineAmbientShadowColor = outcome.color()
                        outlineSpotShadowColor = outcome.color()
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
                    ).apply {
                        if (i == 0) marginEnd = dp(6) else marginStart = dp(6)
                    }
                    setOnClickListener { if (allowClick(250L)) selectOutcomeAndHide(outcome.id) }
                    if (outcome.id == Outcome.INFO.id) {
                        setOnLongClickListener { showEditInfoMessageDialog(); true }
                    }
                }
                row.addView(button)
            }
            container.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showManageOutcomesDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manage_outcomes, null)
        val etLabel = dialogView.findViewById<android.widget.EditText>(R.id.etNewOutcomeLabel)
        val btnAdd = dialogView.findViewById<android.widget.TextView>(R.id.btnAddOutcome)
        val existingContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.existingOutcomesContainer)
        val btnClose = dialogView.findViewById<android.widget.TextView>(R.id.btnCloseManageOutcomes)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        fun refreshList() {
            existingContainer.removeAllViews()
            outcomeStore.customOutcomes().forEach { outcome ->
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                val badge = android.widget.TextView(this).apply {
                    text = outcome.label
                    setTextColor(outcome.textColor())
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(20).toFloat()
                        setColor(outcome.color())
                    }
                    background = bg
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                val remove = android.widget.TextView(this).apply {
                    text = "Remove"
                    setTextColor(android.graphics.Color.parseColor("#FF3B5C"))
                    textSize = 12f
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener {
                        outcomeStore.deleteCustom(outcome.id)
                        refreshList()
                        renderOutcomeOverlay()
                    }
                }
                row.addView(badge)
                row.addView(remove)
                existingContainer.addView(row)
            }
        }
        refreshList()

        btnAdd.setOnClickListener {
            val label = etLabel.text.toString()
            val added = outcomeStore.addCustom(label)
            if (added != null) {
                etLabel.setText("")
                refreshList()
                renderOutcomeOverlay()
                showToast("'${added.label}' added.")
            } else {
                showToast("Enter an outcome label.")
            }
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun refreshDashboard() {
        val leads = engine.leads
        val total = leads.size
        val completed = leads.count { it.status == CallSequencer.Status.COMPLETED }
        val skipped = leads.count { it.status == CallSequencer.Status.SKIPPED }

        val hasList = engine.hasActiveList()
        binding.llListSummary.visibility = if (hasList) View.VISIBLE else View.GONE
        binding.llPasteArea.visibility = if (hasList) View.GONE else View.VISIBLE
        binding.tvNumbersLabel.visibility = if (hasList) View.VISIBLE else View.GONE
        binding.dockControls.visibility = if (hasList) View.VISIBLE else View.GONE

        if (hasList) {
            binding.tvListSummaryCount.text = "$total contacts • ${completed + skipped} dialed"
        }
    }

    // ---------- CallEngineListener ----------

    override fun onLeadUpdated(index: Int, status: CallSequencer.Status) {
        runOnUiThread {
            adapter.refreshRow(index)
            refreshDashboard()
        }
    }

    override fun onDialing(index: Int, lead: Lead) {
        runOnUiThread {
            val label = if (lead.name.isNullOrBlank()) lead.phone else "${lead.name} (${lead.phone})"
            binding.tvListSummaryCount.text = "Dialing: $label (${index + 1}/${engine.leads.size})"
            binding.btnFloatingStop.visibility = View.VISIBLE
        }
    }

    override fun onCallEndedAwaitingOutcome(index: Int) {
        runOnUiThread {
            hideKeyboard()
            binding.overlayOutcome.visibility = View.VISIBLE
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let { focusedView ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
            focusedView.clearFocus()
        }
    }

    override fun onBatchComplete(callsDone: Int) {
        runOnUiThread {
            binding.btnFloatingStop.visibility = View.GONE
            showToast("Batch limit of $callsDone calls reached. Tap Start to continue.", Toast.LENGTH_LONG)
            refreshDashboard()
        }
    }

    override fun onSessionPaused() {
        runOnUiThread { refreshDashboard() }
    }

    override fun onSessionResumed() {
        runOnUiThread {
            binding.btnFloatingStop.visibility = View.VISIBLE
        }
    }

    override fun onSessionStopped() {
        runOnUiThread {
            binding.overlayOutcome.visibility = View.GONE
            binding.btnFloatingStop.visibility = View.GONE
            refreshDashboard()
        }
    }

    override fun onSessionComplete() {
        runOnUiThread {
            showToast("All calls completed and saved to Call Sheets.", Toast.LENGTH_LONG)
            clearListUi()
        }
    }

    override fun onEngineError(message: String) {
        runOnUiThread {
            val cleanMsg = message.ifBlank { "Unable to place call. Please check network signal and SIM settings." }
            showToast(cleanMsg, Toast.LENGTH_LONG)
        }
    }

    override fun onLog(message: String) {
        // Handled internally via SafeLog
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                requestNotificationPermissionOnce()
            } else {
                showToast("Phone and Call State permissions are required to place calls.", Toast.LENGTH_LONG)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        subscriptionManager.refreshStatusInBackground()
        val draft = numberDraftStore.getDraft()
        if (draft != binding.etNumbers.text.toString()) {
            binding.etNumbers.setText(draft)
        }
        if (pendingInfoOutcome && ::engine.isInitialized) {
            pendingInfoOutcome = false
            engine.selectOutcome(Outcome.INFO.id)
        }
        authManager.checkSessionInBackground {
            runOnUiThread {
                showToast("Session signed out because this account was accessed on another device.", Toast.LENGTH_LONG)
                authManager.logout()
                if (::engine.isInitialized) engine.stop()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }
    }

    private fun requestNotificationPermissionOnce() {
        val requiredGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (requiredGranted && android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        draftHandler.removeCallbacks(draftSaveRunnable)
        if (::engine.isInitialized) engine.teardown()
    }

    override fun onPause() {
        super.onPause()
        draftHandler.removeCallbacks(draftSaveRunnable)
        saveDraftNow()
        if (::engine.isInitialized) engine.persist()
    }
}
