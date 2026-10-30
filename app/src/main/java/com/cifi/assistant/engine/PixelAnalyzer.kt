package com.cifi.assistant.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.cifi.assistant.model.PageId

object PixelAnalyzer {

    /**
     * Fast sub-rectangle pixel scan to detect if the active screen is
     * Profile Inventory or Profile Stats tab using the cyan highlight button bar.
     * Takes ~1-2ms using Bitmap.getPixels.
     */
    fun detectProfileTab(bitmap: Bitmap): PageId? {
        val w = bitmap.width
        val h = bitmap.height

        val startX = (w * 0.06).toInt().coerceIn(0, w - 1)
        val startY = (h * 0.355).toInt().coerceIn(0, h - 1)
        val scanW = (w * 0.88).toInt().coerceAtMost(w - startX)
        val scanH = (h * 0.035).toInt().coerceAtMost(h - startY)

        if (scanW <= 0 || scanH <= 0) return null

        val pixels = IntArray(scanW * scanH)
        try {
            bitmap.getPixels(pixels, 0, scanW, startX, startY, scanW, scanH)
        } catch (e: Exception) {
            return null
        }

        val halfW = scanW / 2
        var leftCyan = 0
        var rightCyan = 0

        for (y in 0 until scanH) {
            val rowOffset = y * scanW
            for (x in 0 until scanW) {
                val pixel = pixels[rowOffset + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Target Cyan highlight: high green and blue, low red
                if (g > 140 && b > 160 && r < 75) {
                    if (x < halfW) leftCyan++ else rightCyan++
                }
            }
        }

        return when {
            leftCyan > 80 && leftCyan >= rightCyan -> PageId.PROFILE_INVENTORY
            rightCyan > 80 -> PageId.PROFILE_STATS
            else -> null
        }
    }
}
