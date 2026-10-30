package com.cifi.assistant.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.cifi.assistant.R
import com.cifi.assistant.engine.OpticalAnalysisEngine
import com.cifi.assistant.repository.DossierRepository
import com.cifi.assistant.service.CaptureService
import com.cifi.assistant.util.ClipboardHelper
import com.cifi.assistant.util.DebugScanStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.hypot

object OverlayController {

    private const val TAG = "OverlayController"
    private const val NORMAL_ALPHA = 0.45f
    private const val ACTIVE_ALPHA = 1.0f
    private val DEFAULT_BORDER_COLOR = Color.parseColor("#22D3EE")

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isOverlayAttached = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)

    private var isScanning = false
    private val fadeIdleRunnable = Runnable {
        fadeAlpha(NORMAL_ALPHA)
    }

    private val resetIconRunnable = Runnable {
        val view = overlayView ?: return@Runnable
        val ivIcon = view.findViewById<ImageView>(R.id.ivOrbIcon)
        val tvPageIcon = view.findViewById<TextView>(R.id.tvCapturedPageIcon)
        val pbScanning = view.findViewById<ProgressBar>(R.id.pbScanning)

        tvPageIcon?.visibility = View.GONE
        pbScanning?.visibility = View.GONE
        ivIcon?.visibility = View.VISIBLE
        resetOrbBorderColor()
        fadeAlpha(NORMAL_ALPHA)
    }

    private val repoListener = {
        updateBadgeText()
    }

    fun isShowing(): Boolean = overlayView?.visibility == View.VISIBLE

    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        mainHandler.post {
            if (overlayView != null) {
                overlayView?.visibility = View.VISIBLE
                updateBadgeText()
                scheduleFadeIdle()
                return@post
            }

            try {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val themedContext = ContextThemeWrapper(context, R.style.Theme_CifiAssistant)
                val inflater = LayoutInflater.from(themedContext)
                val view = inflater.inflate(R.layout.overlay_floating_circle, null)
                overlayView = view

                // Start translucent
                view.alpha = NORMAL_ALPHA

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 16
                    y = 280
                }
                layoutParams = params

                val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                var initialX = 0
                var initialY = 0
                var initialTouchX = 0f
                var initialTouchY = 0f
                var isDragMoved = false

                view.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            mainHandler.removeCallbacks(fadeIdleRunnable)
                            mainHandler.removeCallbacks(resetIconRunnable)
                            fadeAlpha(ACTIVE_ALPHA)

                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragMoved = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY

                            if (!isDragMoved && hypot(dx, dy) > touchSlop) {
                                isDragMoved = true
                            }

                            if (isDragMoved) {
                                params.x = initialX + dx.toInt()
                                params.y = initialY + dy.toInt()
                                try {
                                    windowManager?.updateViewLayout(overlayView, params)
                                } catch (_: Exception) {}
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (isDragMoved) {
                                snapToEdge(params)
                                scheduleFadeIdle()
                            } else {
                                handleOrbTap(context)
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            scheduleFadeIdle()
                            false
                        }
                        else -> false
                    }
                }

                windowManager?.addView(view, params)
                isOverlayAttached = true
                DossierRepository.addListener(repoListener)
                updateBadgeText()
                scheduleFadeIdle()
                Log.i(TAG, "Floating circle overlay initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inflate and attach circular overlay: ${e.message}", e)
            }
        }
    }

    private fun handleOrbTap(context: Context) {
        if (isScanning) return
        isScanning = true
        DebugScanStorage.init(context)

        mainHandler.removeCallbacks(fadeIdleRunnable)
        mainHandler.removeCallbacks(resetIconRunnable)
        fadeAlpha(ACTIVE_ALPHA)

        val view = overlayView ?: return
        val ivIcon = view.findViewById<ImageView>(R.id.ivOrbIcon)
        val tvPageIcon = view.findViewById<TextView>(R.id.tvCapturedPageIcon)
        val pbScanning = view.findViewById<ProgressBar>(R.id.pbScanning)

        ivIcon?.visibility = View.INVISIBLE
        tvPageIcon?.visibility = View.GONE
        pbScanning?.visibility = View.VISIBLE

        // 1. Temporarily hide overlay so it NEVER appears in screenshot
        view.visibility = View.INVISIBLE

        // 2. Allow 80ms for SurfaceFlinger to compose the clean game frame
        mainHandler.postDelayed({
            CaptureService.captureSingleFrame(context) { bitmap ->
                // 3. Immediately restore overlay visibility
                view.visibility = View.VISIBLE

                if (bitmap == null) {
                    isScanning = false
                    showFeedbackIcon("⚠️", Color.parseColor("#F43F5E"))
                    return@captureSingleFrame
                }

                scope.launch {
                    try {
                        // 4. Optical Analysis & Classification
                        val pageId = OpticalAnalysisEngine.processScreenshot(bitmap)

                        // 5. Automatically compile full dossier and copy to clipboard SILENTLY (No toasts!)
                        val fullDossier = DossierRepository.compileDossierString()
                        ClipboardHelper.copyToClipboard(context, "CIFI Dossier", fullDossier, showToast = false)

                        // 6. Visual Orb Feedback: Display the captured page icon & accent border
                        val accent = try {
                            Color.parseColor(pageId.accentColor)
                        } catch (_: Exception) {
                            DEFAULT_BORDER_COLOR
                        }
                        showFeedbackIcon(pageId.icon, accent)
                        updateBadgeText()
                    } catch (e: Exception) {
                        Log.e(TAG, "Scanning failed: ${e.message}", e)
                        showFeedbackIcon("⚠️", Color.parseColor("#F43F5E"))
                    } finally {
                        isScanning = false
                    }
                }
            }
        }, 80)
    }

    private fun showFeedbackIcon(iconText: String, strokeColor: Int) {
        mainHandler.post {
            val view = overlayView ?: return@post
            val ivIcon = view.findViewById<ImageView>(R.id.ivOrbIcon)
            val tvPageIcon = view.findViewById<TextView>(R.id.tvCapturedPageIcon)
            val pbScanning = view.findViewById<ProgressBar>(R.id.pbScanning)

            pbScanning?.visibility = View.GONE
            ivIcon?.visibility = View.GONE

            tvPageIcon?.apply {
                text = iconText
                visibility = View.VISIBLE
            }

            setOrbBorderColor(strokeColor)

            // Tactile visual pop animation (scale 1.0 -> 1.2 -> 1.0)
            view.animate()
                .scaleX(1.22f)
                .scaleY(1.22f)
                .setDuration(160)
                .setInterpolator(OvershootInterpolator(2.0f))
                .withEndAction {
                    view.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(160)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()

            // Keep icon visible and full alpha for 2.2 seconds before reverting to scan orb
            mainHandler.removeCallbacks(resetIconRunnable)
            mainHandler.postDelayed(resetIconRunnable, 2200)
        }
    }

    private fun setOrbBorderColor(color: Int) {
        val view = overlayView ?: return
        val root = view.findViewById<FrameLayout>(R.id.overlayCircleRoot) ?: return
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E6080616"))
            setStroke(5, color)
        }
        root.background = drawable
    }

    private fun resetOrbBorderColor() {
        setOrbBorderColor(DEFAULT_BORDER_COLOR)
    }

    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val windowManager = this.windowManager ?: return
        val view = overlayView ?: return

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val viewWidth = view.width.coerceAtLeast(100)

        val targetX = if (params.x + (viewWidth / 2) < screenWidth / 2) 16 else (screenWidth - viewWidth - 16)

        val animator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                params.x = va.animatedValue as Int
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {}
            }
        }
        animator.start()
    }

    private fun fadeAlpha(targetAlpha: Float) {
        val view = overlayView ?: return
        view.animate()
            .alpha(targetAlpha)
            .setDuration(250)
            .start()
    }

    private fun scheduleFadeIdle() {
        mainHandler.removeCallbacks(fadeIdleRunnable)
        mainHandler.postDelayed(fadeIdleRunnable, 1800)
    }

    private fun updateBadgeText() {
        mainHandler.post {
            val view = overlayView ?: return@post
            val tvBadge = view.findViewById<TextView>(R.id.tvOrbCounterBadge) ?: return@post
            val count = DossierRepository.getStoredCount()
            tvBadge.text = "$count/9"
        }
    }

    fun hideOverlay() {
        mainHandler.post {
            mainHandler.removeCallbacks(fadeIdleRunnable)
            mainHandler.removeCallbacks(resetIconRunnable)
            overlayView?.visibility = View.GONE
        }
    }

    fun destroy() {
        mainHandler.post {
            mainHandler.removeCallbacks(fadeIdleRunnable)
            mainHandler.removeCallbacks(resetIconRunnable)
            if (isOverlayAttached && overlayView != null) {
                try {
                    DossierRepository.removeListener(repoListener)
                    windowManager?.removeView(overlayView)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing overlay view: ${e.message}")
                }
            }
            overlayView = null
            isOverlayAttached = false
        }
    }
}
