package com.cifi.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
            // Android 14+ mandate: start foreground service first, then obtain projection token inside service
            CaptureService.start(this, result.resultCode, result.data!!)
            Toast.makeText(this, "Assistant active! Use the notification to hide/show the orb anytime.", Toast.LENGTH_SHORT).show()
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

    private val overlayStateListener = {
        runOnUiThread {
            updateStatus()
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
        OverlayController.addStateListener(overlayStateListener)
    }

    override fun onResume() {
        super.onResume()
        if (!CaptureService.isServiceRunning && CaptureService.mediaProjection == null) {
            isProjectionAuthorized = false
        }
        updateStatus()
        updateBankDisplay()
    }

    override fun onDestroy() {
        DossierRepository.removeListener(repoListener)
        OverlayController.removeStateListener(overlayStateListener)
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

        // 2. Screen Capture Permission
        binding.btnPermMediaProjection.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant Floating Overlay permission (Step 1) first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (CaptureService.mediaProjection == null) {
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            } else {
                Toast.makeText(this, "Screen capture session is already active", Toast.LENGTH_SHORT).show()
            }
        }

        // Show / Hide Floating Orb Button
        binding.btnTestOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant Floating Overlay permission first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (OverlayController.isShowing()) {
                OverlayController.hideOverlay(this)
            } else {
                OverlayController.showOverlay(this)
            }
            updateStatus()
        }

        // Turn Off / Turn On Assistant Button
        binding.btnTurnOffAssistant.setOnClickListener {
            val isRunning = isProjectionAuthorized || CaptureService.mediaProjection != null || OverlayController.isShowing()

            if (isRunning) {
                // Turn off everything cleanly
                CaptureService.stop(this)
                isProjectionAuthorized = false
                updateStatus()
                Toast.makeText(this, "CIFI Assistant turned off", Toast.LENGTH_SHORT).show()
            } else {
                // Turn on
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Please grant Floating Overlay permission (Step 1) first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
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

    private fun updateStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasProjection = isProjectionAuthorized || CaptureService.mediaProjection != null
        val isOrbShowing = OverlayController.isShowing()

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

        // Update Show / Hide Floating Orb button
        binding.btnTestOverlay.text = if (isOrbShowing) "Hide Floating Orb" else "Show Floating Orb"

        // Update Turn Off / Turn On button appearance
        val isRunning = hasProjection || isOrbShowing
        if (!isRunning) {
            binding.btnTurnOffAssistant.text = "Turn On Assistant"
            binding.btnTurnOffAssistant.setTextColor(ContextCompat.getColor(this, R.color.emerald_400))
        } else {
            binding.btnTurnOffAssistant.text = "Turn Off Assistant"
            binding.btnTurnOffAssistant.setTextColor(ContextCompat.getColor(this, R.color.rose_500))
        }

        // Overall status banner
        if (hasOverlay && hasProjection) {
            binding.tvOverallStatus.text = if (isOrbShowing) {
                "Companion Active • Orb Visible"
            } else {
                "Companion Active • Orb Hidden"
            }
            binding.tvOverallStatus.setTextColor(ContextCompat.getColor(this, R.color.emerald_400))
            binding.tvOverallStatusDesc.text = "Use the notification bar (or the buttons below) to hide/show the floating orb or turn off the assistant anytime."
        } else {
            val missing = mutableListOf<String>()
            if (!hasOverlay) missing.add("Floating Overlay")
            if (!hasProjection) missing.add("Screen Capture")
            binding.tvOverallStatus.text = "Assistant Inactive (${missing.joinToString(", ")})"
            binding.tvOverallStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_400))
            binding.tvOverallStatusDesc.text = "Grant Floating Overlay and tap 'Authorize' (or 'Turn On Assistant') to start scanning."
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
