package com.cifi.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cifi.assistant.databinding.ActivityMainBinding
import com.cifi.assistant.model.PageId
import com.cifi.assistant.overlay.OverlayController
import com.cifi.assistant.repository.DossierRepository
import com.cifi.assistant.service.CaptureService
import com.cifi.assistant.service.CifiAccessibilityService
import com.cifi.assistant.util.ClipboardHelper
import com.cifi.assistant.util.DebugScanStorage
import com.cifi.assistant.util.NotificationHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isProjectionAuthorized = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission handled
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            isProjectionAuthorized = true
            CifiAccessibilityService.setPaused(this, false)
            // Android 14+ mandate: start foreground service first, then obtain projection token inside service
            CaptureService.start(this, result.resultCode, result.data!!)
            Toast.makeText(this, "Screen capture authorized!", Toast.LENGTH_SHORT).show()
        } else {
            isProjectionAuthorized = false
            Toast.makeText(this, "Screen capture permission declined", Toast.LENGTH_SHORT).show()
        }
        updateStatus()
    }

    private val repoListener = {
        runOnUiThread {
            updateBankDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        NotificationHelper.createNotificationChannel(this)
        DebugScanStorage.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setupButtons()
        DossierRepository.addListener(repoListener)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateBankDisplay()
    }

    override fun onDestroy() {
        DossierRepository.removeListener(repoListener)
        super.onDestroy()
    }

    private fun setupButtons() {
        // 1. Overlay Permission
        binding.btnPermOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. Accessibility Permission
        binding.btnPermAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Enable 'CIFI Assistant' in Accessibility services", Toast.LENGTH_LONG).show()
        }

        // 3. Screen Capture Permission
        binding.btnPermMediaProjection.setOnClickListener {
            if (CaptureService.mediaProjection == null) {
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            } else {
                Toast.makeText(this, "Screen capture session is already active", Toast.LENGTH_SHORT).show()
            }
        }

        // Test Floating Overlay Button
        binding.btnTestOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant Floating Overlay permission first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (OverlayController.isShowing()) {
                OverlayController.hideOverlay()
                binding.btnTestOverlay.text = "Test Floating Overlay"
            } else {
                CifiAccessibilityService.setPaused(this, false)
                OverlayController.showOverlay(this)
                binding.btnTestOverlay.text = "Hide Floating Overlay"
            }
            updateStatus()
        }

        // Turn Off / Turn On Assistant Button
        binding.btnTurnOffAssistant.setOnClickListener {
            val isCurrentlyPaused = CifiAccessibilityService.isPaused(this) &&
                    CaptureService.mediaProjection == null &&
                    !OverlayController.isShowing()

            if (!isCurrentlyPaused) {
                // Turn off everything cleanly
                CifiAccessibilityService.setPaused(this, true)
                OverlayController.destroy()
                CaptureService.stop(this)
                isProjectionAuthorized = false
                binding.btnTestOverlay.text = "Test Floating Overlay"
                updateStatus()
                Toast.makeText(this, "CIFI Assistant turned off", Toast.LENGTH_SHORT).show()
            } else {
                // Turn back on
                CifiAccessibilityService.setPaused(this, false)
                if (CaptureService.mediaProjection == null) {
                    mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                } else {
                    updateStatus()
                    Toast.makeText(this, "CIFI Assistant turned on", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Bank Action Buttons
        binding.btnCopyDossier.setOnClickListener {
            val dossier = DossierRepository.compileDossierString()
            ClipboardHelper.copyToClipboard(this, "CIFI Dossier", dossier)
        }

        binding.btnCopyJson.setOnClickListener {
            val json = DossierRepository.compileDossierJson()
            ClipboardHelper.copyToClipboard(this, "CIFI JSON", json)
        }

        binding.btnPreviewDossier.setOnClickListener {
            showPreviewDialog()
        }

        binding.btnClearBank.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Memory Bank")
                .setMessage("Reset all 9 stored pages to empty (all zeroes)?")
                .setPositiveButton("Reset") { _, _ ->
                    DossierRepository.resetAll()
                    Toast.makeText(this, "Memory bank reset", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (CifiAccessibilityService.isServiceConnected) return true
        val serviceName = "$packageName/${CifiAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceName)
    }

    private fun updateStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasProjection = isProjectionAuthorized || CaptureService.mediaProjection != null
        val isPaused = CifiAccessibilityService.isPaused(this)

        // Update Overlay Button & Label
        if (hasOverlay) {
            binding.btnPermOverlay.text = "Granted ✓"
            binding.btnPermOverlay.isEnabled = false
            binding.btnPermOverlay.setTextColor(ContextCompat.getColor(this, R.color.emerald_400))
        } else {
            binding.btnPermOverlay.text = "Grant"
            binding.btnPermOverlay.isEnabled = true
            binding.btnPermOverlay.setTextColor(ContextCompat.getColor(this, R.color.cyan_400))
        }

        // Update Accessibility Button
        if (hasAccessibility) {
            binding.btnPermAccessibility.text = "Active ✓"
            binding.btnPermAccessibility.isEnabled = false
            binding.btnPermAccessibility.setTextColor(ContextCompat.getColor(this, R.color.emerald_400))
        } else {
            binding.btnPermAccessibility.text = "Enable"
            binding.btnPermAccessibility.isEnabled = true
            binding.btnPermAccessibility.setTextColor(ContextCompat.getColor(this, R.color.cyan_400))
        }

        // Update Projection Button
        if (hasProjection) {
            binding.btnPermMediaProjection.text = "Active ✓"
            binding.btnPermMediaProjection.isEnabled = false
            binding.btnPermMediaProjection.setTextColor(ContextCompat.getColor(this, R.color.emerald_400))
        } else {
            binding.btnPermMediaProjection.text = "Authorize"
            binding.btnPermMediaProjection.isEnabled = true
            binding.btnPermMediaProjection.setTextColor(ContextCompat.getColor(this, R.color.cyan_400))
        }

        // Update Turn Off / Turn On button appearance
        val isCompletelyOff = isPaused && !hasProjection && !OverlayController.isShowing()
        if (isCompletelyOff) {
            binding.btnTurnOffAssistant.text = "Turn On Assistant"
            binding.btnTurnOffAssistant.setTextColor(ContextCompat.getColor(this, R.color.emerald_400))
        } else {
            binding.btnTurnOffAssistant.text = "Turn Off Assistant"
            binding.btnTurnOffAssistant.setTextColor(ContextCompat.getColor(this, R.color.rose_500))
        }

        // Overall status banner
        if (isCompletelyOff) {
            binding.tvOverallStatus.text = "Assistant Turned Off"
            binding.tvOverallStatus.setTextColor(ContextCompat.getColor(this, R.color.rose_500))
            binding.tvOverallStatusDesc.text = "All background capture and overlay services are stopped. Tap 'Turn On Assistant' to resume."
        } else if (hasOverlay && hasAccessibility && hasProjection) {
            binding.tvOverallStatus.text = "Companion Ready (0% Idle CPU)"
            binding.tvOverallStatus.setTextColor(ContextCompat.getColor(this, R.color.emerald_400))
            binding.tvOverallStatusDesc.text = "Open CIFI to view the floating overlay. The companion will automatically display when the game launches."
        } else {
            val missing = mutableListOf<String>()
            if (!hasOverlay) missing.add("Overlay")
            if (!hasAccessibility) missing.add("Accessibility")
            if (!hasProjection) missing.add("Screen Capture")
            binding.tvOverallStatus.text = "Action Required: Missing ${missing.joinToString(", ")}"
            binding.tvOverallStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_400))
            binding.tvOverallStatusDesc.text = "Complete the required setup above to enable automatic detection and scanning."
        }
    }

    private fun updateBankDisplay() {
        val count = DossierRepository.getStoredCount()
        binding.tvBankCounter.text = "$count / 9 Captured"

        val sb = StringBuilder()
        val items = PageId.entries.map { p ->
            val isUp = DossierRepository.isPageUploaded(p)
            val mark = if (isUp) "✓" else "✕"
            "[${p.icon} ${p.shortName}: $mark]"
        }

        for (i in items.indices step 3) {
            val chunk = items.subList(i, minOf(i + 3, items.size))
            sb.append(chunk.joinToString(" ")).append("\n")
        }

        binding.tvPageSlotsStatus.text = sb.toString().trim()
    }

    private fun showPreviewDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_dossier_preview, null)
        val tvContent = dialogView.findViewById<TextView>(R.id.tvDossierContent)
        val btnCopy = dialogView.findViewById<Button>(R.id.btnCopyDossierInDialog)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseDialog)

        val report = DossierRepository.compileDossierString()
        tvContent.text = report

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCopy.setOnClickListener {
            ClipboardHelper.copyToClipboard(this, "CIFI Dossier", report)
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
