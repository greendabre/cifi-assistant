package com.cifi.assistant.service

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.cifi.assistant.overlay.OverlayController
import com.cifi.assistant.repository.DossierRepository
import com.cifi.assistant.util.ClipboardHelper
import com.cifi.assistant.util.DebugScanStorage
import com.cifi.assistant.util.NotificationHelper
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_START = "com.cifi.assistant.action.START_CAPTURE_SERVICE"
        const val ACTION_TOGGLE_OVERLAY = "com.cifi.assistant.action.TOGGLE_OVERLAY"
        const val ACTION_COPY_DOSSIER = "com.cifi.assistant.action.COPY_DOSSIER"
        const val ACTION_STOP = "com.cifi.assistant.action.STOP_CAPTURE_SERVICE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        var mediaProjection: MediaProjection? = null
        var isServiceRunning = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            OverlayController.destroy(context)
            mediaProjection?.stop()
            mediaProjection = null
            isServiceRunning = false
            val intent = Intent(context, CaptureService::class.java)
            context.stopService(intent)
        }

        /**
         * Captures a single frame on demand.
         * Creates an ImageReader and VirtualDisplay, grabs the latest image,
         * extracts Bitmap, and immediately releases both to maintain 0.0% idle CPU and zero RAM leak.
         */
        fun captureSingleFrame(context: Context, onBitmapReady: (Bitmap?) -> Unit) {
            val projection = mediaProjection
            if (projection == null) {
                Log.w(TAG, "Cannot capture frame: mediaProjection is null (permission not granted)")
                onBitmapReady(null)
                return
            }

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            var imageReader: ImageReader? = null
            var virtualDisplay: VirtualDisplay? = null
            val isCaptured = AtomicBoolean(false)
            val mainHandler = Handler(Looper.getMainLooper())

            try {
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                virtualDisplay = projection.createVirtualDisplay(
                    "CifiSingleCapture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    null
                )

                imageReader.setOnImageAvailableListener({ reader ->
                    if (isCaptured.compareAndSet(false, true)) {
                        var bitmapResult: Bitmap? = null
                        try {
                            val image = reader.acquireLatestImage()
                            if (image != null) {
                                val planes = image.planes
                                val buffer = planes[0].buffer
                                val pixelStride = planes[0].pixelStride
                                val rowStride = planes[0].rowStride
                                val rowPadding = rowStride - pixelStride * width

                                val fullBitmap = Bitmap.createBitmap(
                                    width + rowPadding / pixelStride,
                                    height,
                                    Bitmap.Config.ARGB_8888
                                )
                                fullBitmap.copyPixelsFromBuffer(buffer)
                                image.close()

                                // Crop out padding to get exact frame
                                bitmapResult = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error acquiring frame from ImageReader: ${e.message}", e)
                        } finally {
                            // Immediately tear down VirtualDisplay and close ImageReader
                            try {
                                virtualDisplay?.release()
                                imageReader?.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error tearing down VirtualDisplay: ${e.message}")
                            }
                        }

                        mainHandler.post {
                            onBitmapReady(bitmapResult)
                        }
                    }
                }, mainHandler)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create VirtualDisplay: ${e.message}", e)
                try {
                    virtualDisplay?.release()
                    imageReader?.close()
                } catch (_: Exception) {}
                onBitmapReady(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DebugScanStorage.init(this)
        NotificationHelper.createNotificationChannel(this)
        isServiceRunning = true
        startInForeground(OverlayController.isShowing())
    }

    private fun startInForeground(isOverlayVisible: Boolean) {
        val notification = NotificationHelper.buildForegroundNotification(this, isOverlayVisible)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                OverlayController.destroy(this)
                mediaProjection?.stop()
                mediaProjection = null
                isServiceRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_OVERLAY -> {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Grant Floating Overlay permission in CIFI Assistant first", Toast.LENGTH_SHORT).show()
                } else if (OverlayController.isShowing()) {
                    OverlayController.hideOverlay(this)
                } else {
                    OverlayController.showOverlay(this)
                }
                return START_STICKY
            }
            ACTION_COPY_DOSSIER -> {
                val dossier = DossierRepository.compileDossierString()
                ClipboardHelper.copyToClipboard(this, "CIFI Dossier", dossier, showToast = true)
                return START_STICKY
            }
        }

        // 1. Ensure foreground service is actively running with mediaProjection type
        isServiceRunning = true
        startInForeground(OverlayController.isShowing())

        // 2. Extract projection token while inside the active foreground service (Android 14+ mandate)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            try {
                val projection = mpManager.getMediaProjection(resultCode, resultData)
                // Register callback as mandated by Android 14+
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        mediaProjection = null
                    }
                }, Handler(Looper.getMainLooper()))

                mediaProjection = projection
                Log.i(TAG, "MediaProjection successfully acquired within active Foreground Service")

                // Automatically show the floating scanner orb once capture is authorized
                if (Settings.canDrawOverlays(this)) {
                    OverlayController.showOverlay(this)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get MediaProjection in foreground service: ${e.message}", e)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isServiceRunning = false
        mediaProjection?.stop()
        mediaProjection = null
        OverlayController.destroy(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
