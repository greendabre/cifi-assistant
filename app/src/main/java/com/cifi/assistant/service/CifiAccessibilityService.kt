package com.cifi.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.cifi.assistant.overlay.OverlayController

class CifiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CifiAccessibility"
        private const val PREFS_NAME = "cifi_assistant_prefs"
        private const val KEY_ASSISTANT_PAUSED = "assistant_paused"
        const val TARGET_PACKAGE = "com.OctocubeGamesCompany.CIFI"

        // Packages that frequently display overlays/dialogs/keyboards over games
        // and should NOT cause the companion overlay to hide
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.samsung.android.game.gametools",
            "com.samsung.android.game.gamehome",
            "com.samsung.android.game.gos",
            "com.samsung.android.honeyboard",
            "com.google.android.inputmethod.latin",
            "com.cifi.assistant"
        )

        var isServiceConnected = false
            private set

        @Volatile
        var isAssistantPaused = false
            private set

        fun isPaused(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            isAssistantPaused = prefs.getBoolean(KEY_ASSISTANT_PAUSED, false)
            return isAssistantPaused
        }

        fun setPaused(context: Context, paused: Boolean) {
            isAssistantPaused = paused
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ASSISTANT_PAUSED, paused)
                .apply()
            if (paused) {
                OverlayController.destroy()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        isPaused(this)
        Log.i(TAG, "CifiAccessibilityService connected and active (paused=$isAssistantPaused)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (isPaused(this)) {
            OverlayController.hideOverlay()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val currentPackage = event.packageName?.toString() ?: return

        // If system UI, keyboard, or Game Booster fires an event, ignore it to keep overlay visible in game
        if (currentPackage in IGNORED_PACKAGES) {
            return
        }

        if (currentPackage == TARGET_PACKAGE) {
            Log.d(TAG, "CIFI foreground detected ($currentPackage) -> Displaying companion overlay")
            OverlayController.showOverlay(this)
        } else {
            // User actually switched to another app or home screen
            Log.d(TAG, "Switched to $currentPackage -> Hiding companion overlay")
            OverlayController.hideOverlay()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "CifiAccessibilityService interrupted")
    }

    override fun onDestroy() {
        isServiceConnected = false
        OverlayController.destroy()
        super.onDestroy()
    }
}
