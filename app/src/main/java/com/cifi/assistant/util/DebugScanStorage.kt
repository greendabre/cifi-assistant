package com.cifi.assistant.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.cifi.assistant.engine.RecognizedLine
import com.cifi.assistant.model.PageData
import com.cifi.assistant.model.PageId
import com.cifi.assistant.repository.DossierRepository
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Retains captured screenshots, raw OCR lines/bounding boxes, and extracted dossier data
 * on device storage (/sdcard/Android/data/com.cifi.assistant/files/debug_scans/)
 * so every scan can be inspected and debugged directly.
 */
object DebugScanStorage {

    private const val TAG = "DebugScanStorage"
    @Volatile
    private var debugDir: File? = null

    fun init(context: Context) {
        if (debugDir == null) {
            val baseDir = context.applicationContext.getExternalFilesDir("debug_scans")
                ?: File(context.applicationContext.filesDir, "debug_scans")
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }
            debugDir = baseDir
            Log.i(TAG, "Debug scan storage initialized at: ${baseDir.absolutePath}")
        }
    }

    fun getDebugDirPath(): String? = debugDir?.absolutePath

    fun saveScanDebugArtifacts(
        pageId: PageId,
        bitmap: Bitmap,
        lines: List<RecognizedLine>,
        extractedData: PageData?
    ) {
        val dir = debugDir ?: return
        try {
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val key = pageId.key

            // 1. Save exact screenshot PNG
            val pngFile = File(dir, "${key}_screenshot.png")
            FileOutputStream(pngFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            // 2. Save raw OCR lines and bounding boxes
            val ocrFile = File(dir, "${key}_ocr_raw.txt")
            val ocrBuilder = StringBuilder()
            ocrBuilder.appendLine("=== CIFI OCR RAW DUMP ===")
            ocrBuilder.appendLine("Page: ${pageId.displayName} ($key)")
            ocrBuilder.appendLine("Timestamp: $timestamp")
            ocrBuilder.appendLine("Resolution: ${bitmap.width}x${bitmap.height}")
            ocrBuilder.appendLine("Total Lines: ${lines.size}")
            ocrBuilder.appendLine("==========================================")
            lines.forEach { line ->
                ocrBuilder.appendLine("[${line.left},${line.top}-${line.right},${line.bottom}] (cx=${line.centerX}, cy=${line.centerY}) ${line.text}")
            }
            ocrFile.writeText(ocrBuilder.toString())

            // 3. Save extracted structured data for this screenshot
            val extractedFile = File(dir, "${key}_extracted.txt")
            val extBuilder = StringBuilder()
            extBuilder.appendLine("=== EXTRACTED DATA FOR ${pageId.displayName.uppercase()} ===")
            extBuilder.appendLine("Timestamp: $timestamp")
            extBuilder.appendLine("Resolution: ${bitmap.width}x${bitmap.height}")
            extBuilder.appendLine("------------------------------------------")
            if (extractedData != null) {
                extBuilder.appendLine(extractedData.toFormattedDossier())
            } else {
                extBuilder.appendLine("(No data extracted)")
            }
            extractedFile.writeText(extBuilder.toString())

            // 4. Update latest full dossier & JSON snapshots
            File(dir, "latest_full_dossier.txt").writeText(DossierRepository.compileDossierString())
            File(dir, "latest_full_dossier.json").writeText(DossierRepository.compileDossierJson())

            Log.i(TAG, "Saved debug scan artifacts for $key to ${dir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug scan artifacts for ${pageId.key}: ${e.message}", e)
        }
    }
}
