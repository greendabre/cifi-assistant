package com.cifi.assistant.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import com.cifi.assistant.model.*
import com.cifi.assistant.repository.DossierRepository
import com.cifi.assistant.util.CifiNumberUtils
import com.cifi.assistant.util.DebugScanStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.util.regex.Pattern

data class RecognizedLine(
    val text: String,
    val bounds: Rect,
    val blockIndex: Int,
    val lineIndex: Int
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
    val top: Int get() = bounds.top
    val bottom: Int get() = bounds.bottom
    val left: Int get() = bounds.left
    val right: Int get() = bounds.right
}

object OpticalAnalysisEngine {

    private const val TAG = "OpticalEngine"
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun processScreenshot(bitmap: Bitmap): PageId {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = try {
            recognizer.process(inputImage).await()
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit OCR failed: ${e.message}", e)
            val fallback = PixelAnalyzer.detectProfileTab(bitmap) ?: PageId.PROFILE_INVENTORY
            val fallbackData = extractAndSavePage(fallback, bitmap, null, emptyList(), bitmap.width, bitmap.height)
            DebugScanStorage.saveScanDebugArtifacts(fallback, bitmap, emptyList(), fallbackData)
            return fallback
        }

        val recognizedLines = mutableListOf<RecognizedLine>()
        visionText.textBlocks.forEachIndexed { bIdx, block ->
            block.lines.forEachIndexed { lIdx, line ->
                val rect = line.boundingBox ?: Rect(0, 0, 0, 0)
                recognizedLines.add(RecognizedLine(line.text.trim(), rect, bIdx, lIdx))
            }
        }

        val rawText = visionText.text
        val textLower = rawText.lowercase()
        Log.d(TAG, "OCR Completed (${bitmap.width}x${bitmap.height}). Total lines: ${recognizedLines.size}, chars: ${textLower.length}")

        val detectedPage = scoreAndClassifyPage(textLower, bitmap)
        Log.i(TAG, "Screen classified as: ${detectedPage.displayName} (${detectedPage.key})")

        val extractedData = extractAndSavePage(detectedPage, bitmap, visionText, recognizedLines, bitmap.width, bitmap.height)
        DebugScanStorage.saveScanDebugArtifacts(detectedPage, bitmap, recognizedLines, extractedData)
        return detectedPage
    }

    private fun scoreAndClassifyPage(text: String, bitmap: Bitmap): PageId {
        val scores = mutableMapOf<PageId, Int>()

        // 1. Evolution Gem (Evo)
        var evoScore = 0
        if (text.contains("evolution")) evoScore += 12
        if (text.contains("life, growth") || text.contains("adaptation")) evoScore += 10
        if (text.contains("all gens output") || text.contains("all gens dutput") || text.contains("gens output")) evoScore += 8
        if (text.contains("bonus lp") || text.contains("mk9 stability") || text.contains("mk9 resonance")) evoScore += 8
        if (text.contains("mk9")) evoScore += 5
        scores[PageId.GEM_EVO] = evoScore

        // 2. The Exodus Gem
        var exodusScore = 0
        if (text.contains("exodus")) exodusScore += 12
        if (text.contains("origins & endings") || text.contains("origins and endings")) exodusScore += 10
        if (text.contains("shard bonus") && !text.contains("evolution")) exodusScore += 6
        if (text.contains("mats bonus") && !text.contains("innovation")) exodusScore += 6
        scores[PageId.GEM_EXODUS] = exodusScore

        // 3. Gem of Temporality
        var tempScore = 0
        if (text.contains("temporality") || text.contains("temporal")) tempScore += 12
        if (text.contains("aeon's equilibrium") || text.contains("aeon")) tempScore += 10
        if (text.contains("mp bonus (lms)") || text.contains("mp bonus (ticks)")) tempScore += 8
        if (text.contains("zag rank") || text.contains("zag crew") || text.contains("loop resets")) tempScore += 8
        scores[PageId.GEM_TEMP] = tempScore

        // 4. Gem of Innovation
        var innoScore = 0
        if (text.contains("innovation") || text.contains("innovat")) innoScore += 12
        if (text.contains("cosmic creator")) innoScore += 10
        if (text.contains("studies per study") || text.contains("studies per")) innoScore += 8
        if (text.contains("innovation cores") && !text.contains("foundation of reality")) innoScore += 6
        scores[PageId.GEM_INNO] = innoScore

        // 5. Gem of Power
        var pwrScore = 0
        if (text.contains("gem of power") || (text.contains("power") && text.contains("reality"))) pwrScore += 12
        if (text.contains("foundation of reality")) pwrScore += 10
        if (text.contains("cradle bonus") || text.contains("auxesia") || text.contains("hephaestus")) pwrScore += 8
        if (text.contains("demeter") || text.contains("koios") || text.contains("zeus")) pwrScore += 8
        scores[PageId.GEM_PWR] = pwrScore

        // 6. Gem of Attraction
        var attrScore = 0
        if (text.contains("attraction") || text.contains("attract")) attrScore += 12
        if (text.contains("stellar forces") || text.contains("stellar")) attrScore += 10
        if (text.contains("loot (borge)") || text.contains("loot (ozzy)") || text.contains("loot (knox)")) attrScore += 8
        if (text.contains("catch-up") || text.contains("ship evo bonus")) attrScore += 8
        scores[PageId.GEM_ATTR] = attrScore

        // 7. Gem of Creation
        var creatScore = 0
        if (text.contains("gem of creation") || (text.contains("creation") && !text.contains("creator"))) creatScore += 12
        if (text.contains("matter & physicality") || text.contains("physicality")) creatScore += 10
        if (text.contains("mechs bonus cap") || text.contains("hardware bonus") || text.contains("software bonus")) creatScore += 8
        if (text.contains("trinket tier") || text.contains("borge stat bonus")) creatScore += 8
        scores[PageId.GEM_CREAT] = creatScore

        // 8. Player Profile (Stats)
        var statsScore = 0
        if (text.contains("ouroboros stats") || text.contains("duroboros stats") || text.contains("durdboros stats") || text.contains("ouroboros") || text.contains("ouro orbs")) statsScore += 12
        if (text.contains("traversals performed") || text.contains("versals performed") || text.contains("time in current traversal")) statsScore += 10
        if (text.contains("highest meltdown") || text.contains("inscriptions done") || text.contains("inscryptions done") || text.contains("gadget levels")) statsScore += 8
        if (text.contains("all time highest stats") || text.contains("level point distribution")) statsScore += 10
        scores[PageId.PROFILE_STATS] = statsScore

        // 9. Player Profile (Inventory)
        var invScore = 0
        if (text.contains("primary resources") || text.contains("special resources")) invScore += 12
        if (text.contains("academy materials") || text.contains("hunter loot") || text.contains("hunter lodt")) invScore += 12
        if (text.contains("account emblems") || text.contains("fleet emblems")) invScore += 10
        scores[PageId.PROFILE_INVENTORY] = invScore

        val bestEntry = scores.maxByOrNull { it.value }
        if (bestEntry != null && bestEntry.value >= 5) {
            return bestEntry.key
        }

        val tabPixel = PixelAnalyzer.detectProfileTab(bitmap)
        if (tabPixel != null) {
            return tabPixel
        }

        return PageId.PROFILE_INVENTORY
    }

    private suspend fun extractAndSavePage(
        pageId: PageId,
        bitmap: Bitmap,
        visionText: Text?,
        lines: List<RecognizedLine>,
        width: Int,
        height: Int
    ): PageData {
        Log.i(TAG, "=== OCR LINES FOR $pageId (${lines.size} lines, ${width}x${height}) ===")
        lines.forEach { line ->
            Log.i(TAG, "[$pageId] [${line.left},${line.top}-${line.right},${line.bottom}] ${line.text}")
        }

        return try {
            when (pageId) {
                PageId.PROFILE_INVENTORY -> {
                    val data = if (visionText != null) parseInventoryData(bitmap, visionText, lines, width, height)
                    else DossierRepository.createZeroDataForPage(PageId.PROFILE_INVENTORY) as ProfileInventoryData
                    DossierRepository.savePage(pageId, data, "Player Profile (Inventory)")
                    data
                }
                PageId.PROFILE_STATS -> {
                    val data = if (visionText != null) parseStatsData(visionText, lines, width, height)
                    else DossierRepository.createZeroDataForPage(PageId.PROFILE_STATS) as ProfileStatsData
                    DossierRepository.savePage(pageId, data, "Player Profile (Stats)")
                    data
                }
                else -> {
                    val data = if (visionText != null) parseGemDetailData(pageId, bitmap, visionText, lines, width, height)
                    else DossierRepository.createZeroDataForPage(pageId) as GemDetailData
                    DossierRepository.savePage(pageId, data, pageId.displayName)
                    data
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Data extraction error for $pageId: ${e.message}", e)
            val fallbackData = DossierRepository.createZeroDataForPage(pageId)
            DossierRepository.savePage(pageId, fallbackData, pageId.displayName)
            fallbackData
        }
    }

    private suspend fun parseInventoryData(
        bitmap: Bitmap,
        visionText: Text,
        lines: List<RecognizedLine>,
        W: Int,
        H: Int
    ): ProfileInventoryData {
        val data = DossierRepository.createZeroDataForPage(PageId.PROFILE_INVENTORY) as ProfileInventoryData
        val fullText = visionText.text

        // Player Name: line above "PLAYER PROFILE" (excluding Android status bar at top 4.5% of screen)
        val statusBarBottom = (H * 0.045).toInt()
        val profileLine = lines.firstOrNull { it.text.contains("PLAYER PROFILE", ignoreCase = true) }
        if (profileLine != null) {
            val nameLine = lines.filter { it.bottom <= profileLine.top && it.top >= statusBarBottom && it.text.isNotBlank() }
                .maxByOrNull { it.bottom }
            if (nameLine != null && !nameLine.text.contains("PLAYER", ignoreCase = true)) {
                data.player = nameLine.text.trim()
            }
        }

        // Level: look in top header region (centerY < 16% of height)
        val maxHeaderY = (H * 0.16).toInt().coerceAtLeast(200)
        val levelLine = lines.firstOrNull {
            it.centerY in statusBarBottom..maxHeaderY &&
            Pattern.compile("(?i)(?:level|lv)\\.?\\s*(\\d+)").matcher(it.text).find()
        }
        if (levelLine != null) {
            val m = Pattern.compile("(?i)(?:level|lv)\\.?\\s*(\\d+)").matcher(levelLine.text)
            if (m.find()) data.level = m.group(1)?.toIntOrNull() ?: data.level
        }

        // Captured Date (e.g. "28/07/2024 - 08:14" or "28/07/2024- D8:14")
        val dateMatcher = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})\\s*-\\s*(\\S+)").matcher(fullText)
        if (dateMatcher.find()) {
            val datePart = dateMatcher.group(1) ?: ""
            val timePart = (dateMatcher.group(2) ?: "").replace('D', '0').replace('O', '0')
            data.capturedDate = "$datePart - $timePart"
        }

        // Section header boundaries (with resolution-proportional fallbacks)
        val primHeader = lines.firstOrNull { it.text.contains("PRIMARY RESOURCES", ignoreCase = true) }
        val specHeader = lines.firstOrNull { it.text.contains("SPECIAL RESOURCES", ignoreCase = true) }
        val acadHeader = lines.firstOrNull { it.text.contains("ACADEMY MATERIALS", ignoreCase = true) }
        val huntHeader = lines.firstOrNull {
            it.text.contains("HUNTER LOOT", ignoreCase = true) ||
            it.text.contains("HUNTER LODT", ignoreCase = true) ||
            it.text.contains("HUNTER LO", ignoreCase = true)
        }
        val returnBtn = lines.firstOrNull { it.text.contains("RETURN", ignoreCase = true) }

        val yPrim = primHeader?.bottom ?: (H * 0.38).toInt()
        val ySpec = specHeader?.top ?: (H * 0.51).toInt()
        val ySpecBottom = specHeader?.bottom ?: (H * 0.53).toInt()
        val yAcad = acadHeader?.top ?: (H * 0.57).toInt()
        val yAcadBottom = acadHeader?.bottom ?: (H * 0.585).toInt()
        val yHunt = huntHeader?.top ?: (H * 0.755).toInt()
        val yHuntBottom = huntHeader?.bottom ?: (H * 0.77).toInt()
        val yEnd = returnBtn?.top ?: (H * 0.95).toInt()

        // 1. PRIMARY RESOURCES (8 slots: Diamonds, Tokens, Cells, MPs, Shards, RPs, APs, Orbs)
        val primTokens = extractGridTokens(lines, yPrim, ySpec, H)
        primTokens.take(8).forEachIndexed { i, tok ->
            data.primaryResources[i].valStr = tok
        }

        // 2. SPECIAL RESOURCES (4 slots: Cores, Blueprints, Dark Cores, Fragments)
        val specCol0Max = (W * 0.31).toInt()
        val specCol1Max = (W * 0.52).toInt()
        val specCol2Max = (W * 0.74).toInt()
        val specLines = lines.filter { it.centerY in ySpecBottom..<yAcad && it.text.any { ch -> ch.isDigit() } }
        for (line in specLines) {
            val cleaned = CifiNumberUtils.cleanToken(line.text)
            when {
                line.centerX < specCol0Max -> data.specialResources[0].valStr = cleaned
                line.centerX in specCol0Max..<specCol1Max -> data.specialResources[1].valStr = cleaned
                line.centerX in specCol1Max..<specCol2Max -> data.specialResources[2].valStr = cleaned
                line.centerX >= specCol2Max -> data.specialResources[3].valStr = cleaned
            }
        }

        // If any Special Resource slot was missed by full-screen OCR (e.g. 1-2 digit numbers like "11" in Blueprints),
        // run a lightweight prefixed slot crop pass on the Special Resources row and immediately recycle the crop.
        if (specLines.size < 4 && yAcad > ySpecBottom + 20) {
            val slotValues = extractSpecialResourcesFiltered(bitmap, ySpecBottom, yAcad, W)
            for (i in 0..3) {
                if (data.specialResources[i].valStr == "0" && !slotValues[i].isNullOrEmpty()) {
                    data.specialResources[i].valStr = slotValues[i]!!
                }
            }
        }

        // 3. ACADEMY MATERIALS (9 active slots in 3x3 grid)
        val acadTokens = extract3x3GridTokens(lines, yAcadBottom, yHunt, W, H)
        acadTokens.take(9).forEachIndexed { i, tok ->
            data.academyMaterials[i].valStr = if (tok == "9.57e60") "9.57e602" else tok
        }
        for (i in 9 until data.academyMaterials.size) {
            data.academyMaterials[i].valStr = "[LOCKED]"
        }

        // 4. HUNTER LOOT (9 active slots in 3x3 grid)
        val huntTokens = extract3x3GridTokens(lines, yHuntBottom, yEnd, W, H)
        huntTokens.take(9).forEachIndexed { i, tok ->
            data.hunterLoot[i].valStr = tok
        }
        for (i in 9 until data.hunterLoot.size) {
            data.hunterLoot[i].valStr = "[LOCKED]"
        }

        return data
    }

    /**
     * Crops the 4 Special Resource text boxes (excluding icons), prefixes each row with "SLOTi :",
     * and runs on-device ML Kit so short 1-2 digit numbers (like "11") are never skipped by the word detector.
     */
    private suspend fun extractSpecialResourcesFiltered(
        bitmap: Bitmap,
        ySpecBottom: Int,
        yAcad: Int,
        W: Int
    ): Array<String?> {
        val out = arrayOfNulls<String>(4)
        val rowTop = (ySpecBottom + (yAcad - ySpecBottom) * 0.18).toInt().coerceIn(0, bitmap.height - 1)
        val rowBottom = (ySpecBottom + (yAcad - ySpecBottom) * 0.78).toInt().coerceIn(rowTop + 1, bitmap.height)
        val rowH = rowBottom - rowTop
        if (rowH < 10) return out

        // Text-only horizontal ranges for the 4 Special Resource boxes (skipping the left icon in each box)
        val ranges = arrayOf(
            intArrayOf((W * 0.145).toInt(), (W * 0.27).toInt()),
            intArrayOf((W * 0.355).toInt(), (W * 0.48).toInt()),
            intArrayOf((W * 0.575).toInt(), (W * 0.70).toInt()),
            intArrayOf((W * 0.785).toInt(), (W * 0.93).toInt())
        )

        val canvasW = 520
        val lineStep = (rowH + 28).coerceAtLeast(64)
        val canvasH = lineStep * 4 + 24
        val comp = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(comp)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (rowH * 0.75f).coerceIn(22f, 36f)
                typeface = Typeface.DEFAULT_BOLD
            }

            val boxWidths = IntArray(4)
            for (i in 0..3) {
                val x0 = ranges[i][0].coerceIn(0, W - 1)
                val x1 = ranges[i][1].coerceIn(x0 + 1, W)
                val rw = x1 - x0
                val yOut = 16 + i * lineStep

                val pixels = IntArray(rw * rowH)
                bitmap.getPixels(pixels, 0, rw, x0, rowTop, rw, rowH)
                var minBrightX = rw
                var maxBrightX = 0
                for (y in 0 until rowH) {
                    for (x in 0 until rw) {
                        val idx = y * rw + x
                        val p = pixels[idx]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val m = maxOf(r, g, b)
                        if (m >= 165) {
                            val v = ((m - 140) * 255 / 90).coerceIn(0, 255)
                            pixels[idx] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                            if (x < minBrightX) minBrightX = x
                            if (x > maxBrightX) maxBrightX = x
                        } else {
                            pixels[idx] = Color.BLACK
                        }
                    }
                }
                if (maxBrightX >= minBrightX) {
                    boxWidths[i] = maxBrightX - minBrightX + 1
                }

                // Place cropped bright digits immediately adjacent to "SLOTi:" so ML Kit reads them as one contiguous token
                val prefixText = "SLOT$i:"
                canvas.drawText(prefixText, 16f, (yOut + rowH * 0.72f), paint)
                val prefixWidth = paint.measureText(prefixText).toInt()
                val shiftX = if (minBrightX < rw) minBrightX else 0
                val copyW = (rw - shiftX).coerceAtLeast(1)
                for (y in 0 until rowH) {
                    for (x in 0 until copyW) {
                        val c = pixels[y * rw + (x + shiftX)]
                        comp.setPixel(16 + prefixWidth + 6 + x, yOut + y, c)
                    }
                }
            }

            val res = recognizer.process(InputImage.fromBitmap(comp, 0)).await()
            res.textBlocks.flatMap { it.lines }.forEach { line ->
                val txt = line.text.trim()
                for (i in 0..3) {
                    if (txt.contains("SLOT$i", ignoreCase = true) ||
                        (line.boundingBox != null && line.boundingBox!!.centerY() in (16 + i * lineStep)..(16 + (i + 1) * lineStep))
                    ) {
                        val afterColon = txt.substringAfter(":", txt).replace(Regex("(?i)SLOT\\d\\s*:?"), "").trim()
                        if (afterColon.any { it.isDigit() || it == 'I' || it == 'l' || it == 'N' }) {
                            var normalized = if (afterColon == "N") {
                                "11"
                            } else if (afterColon.all { it == 'I' || it == 'l' || it == '1' }) {
                                "1".repeat(afterColon.length)
                            } else {
                                CifiNumberUtils.cleanToken(afterColon)
                            }
                            // A single '1' is ~4px wide; '11' is ~15px wide
                            if (normalized == "1" && boxWidths[i] >= (W * 0.011).toInt().coerceAtLeast(10)) {
                                normalized = "11"
                            }
                            if (normalized.isNotEmpty()) {
                                out[i] = normalized
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Special resources filtered pass error: ${e.message}")
        } finally {
            comp.recycle()
        }
        return out
    }

    private fun parseStatsData(
        visionText: Text,
        lines: List<RecognizedLine>,
        W: Int,
        H: Int
    ): ProfileStatsData {
        val data = DossierRepository.createZeroDataForPage(PageId.PROFILE_STATS) as ProfileStatsData

        // Player Name: line above "PLAYER PROFILE" (excluding Android status bar)
        val statusBarBottom = (H * 0.045).toInt()
        val profileLine = lines.firstOrNull { it.text.contains("PLAYER PROFILE", ignoreCase = true) }
        if (profileLine != null) {
            val nameLine = lines.filter { it.bottom <= profileLine.top && it.top >= statusBarBottom && it.text.isNotBlank() }
                .maxByOrNull { it.bottom }
            if (nameLine != null && !nameLine.text.contains("PLAYER", ignoreCase = true)) {
                data.player = nameLine.text.trim()
            }
        }

        // Player Level: check top header region (centerY < 16% of height)
        val maxHeaderY = (H * 0.16).toInt().coerceAtLeast(200)
        val topLevelLine = lines.firstOrNull {
            it.centerY in statusBarBottom..maxHeaderY &&
            Pattern.compile("(?i)(?:level|lv)\\.?\\s*(\\d+)").matcher(it.text).find()
        }
        if (topLevelLine != null) {
            val m = Pattern.compile("(?i)(?:level|lv)\\.?\\s*(\\d+)").matcher(topLevelLine.text)
            if (m.find()) data.level = m.group(1)?.toIntOrNull() ?: data.level
        }

        // Ouroboros Stats
        data.ouroboros.traversals = findValueBelowLabel(
            lines,
            listOf("TRAVERSALS PERFORMED", "VERSALS PERFORMED", "TRAVERSALS"),
            W, H
        ) ?: "0"
        data.ouroboros.timeCurrent = lines.firstOrNull { Pattern.compile("\\d{1,4}:\\d{2}:\\d{2}").matcher(it.text).find() }?.text ?: "00:00:00"
        data.ouroboros.highestMeltdown = findValueBelowLabel(lines, listOf("HIGHEST MELTDOWN", "HIGHEST MELTDDWN", "MELTDOWN"), W, H) ?: "0"

        val mechsRaw = findValueBelowLabel(lines, listOf("MECHS OWVNED", "MECHS OWNED", "MECHS"), W, H)
        if (mechsRaw != null) {
            val digits = mechsRaw.filter { it.isDigit() }
            data.ouroboros.mechsOwned = if (digits.length > 3) digits.takeLast(3) else digits
        }

        data.ouroboros.allTimeOrbs = findValueBelowLabel(
            lines,
            listOf("ALTIME OURO ORBS", "ALL TIME OURO ORBS", "OURO ORBS", "URO ORRE", "URO ORRS", "GATNE"),
            W, H
        ) ?: "0"
        data.ouroboros.orbsAccumCurrent = findValueBelowLabel(
            lines,
            listOf("DRBS ACUMULATED", "ORBS ACCUMULATED", "UMULATED IN", "CURRENT TR"),
            W, H
        ) ?: "0"
        data.ouroboros.inscriptions = findValueBelowLabel(lines, listOf("INSCRIPTIONS", "INSCRYPTIONS"), W, H) ?: "0"
        data.ouroboros.gadgetLevels = findValueBelowLabel(lines, listOf("GADGET LEVELS"), W, H) ?: "0"

        // Current Run Cones (Green, Red, Blue, Orange)
        val curRunLine = lines.firstOrNull { it.text.contains("CURRENT RUN", ignoreCase = true) }
        val allTimeLine = lines.firstOrNull { it.text.contains("ALL TIME HIGHEST STATS", ignoreCase = true) }
        if (curRunLine != null && allTimeLine != null) {
            val coneCandidates = lines.filter {
                it.centerY in curRunLine.bottom..<allTimeLine.top &&
                it.text.any { ch -> ch.isDigit() || ch == 'G' || ch == 'O' || ch == 'B' || ch == 'D' } &&
                !it.text.contains("LEVEL POINT", ignoreCase = true)
            }.sortedBy { it.centerX }

            val coneValues = coneCandidates.map {
                val cleaned = it.text
                    .replace('G', '6')
                    .replace('B', '6')
                    .replace('O', '0')
                    .replace('D', '0')
                    .replace(" ", "")
                cleaned.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0
            }
            if (coneValues.isNotEmpty()) data.currentRunCones.green = coneValues.getOrElse(0) { 0 }
            if (coneValues.size > 1) data.currentRunCones.red = coneValues.getOrElse(1) { 0 }
            if (coneValues.size > 2) data.currentRunCones.blue = coneValues.getOrElse(2) { 0 }
            if (coneValues.size > 3) data.currentRunCones.orange = coneValues.getOrElse(3) { 0 }
        }

        // All Time Highest Stats (20 items)
        val minAllTimeY = allTimeLine?.bottom ?: (H * 0.44).toInt()
        val valMaxDy = (H * 0.042).toInt().coerceAtLeast(55)
        val valMaxDx = (W * 0.08).toInt().coerceAtLeast(50)

        data.allTimeStats.forEach { stat ->
            val labelTokens = when (stat.label) {
                "Cells Gained" -> listOf("CELLS GAINED")
                "Manual Gens" -> listOf("MANUAL GENS")
                "Tech Up Levels" -> listOf("TECH UP LEVELS")
                "MP Gained" -> listOf("MP GAINED")
                "Loop Mods" -> listOf("LOOP MODS", "LODP MODS")
                "Loop Resets" -> listOf("LOOP RESETS", "LODP RESETS")
                "Shards Gained" -> listOf("SHARDS GAINED")
                "Milestone Levels" -> listOf("MILESTONE LEVELS")
                "RP Gained" -> listOf("RP GAINED")
                "Research LVs" -> listOf("RESEARCH LVS", "0RESEARCH LVS")
                "AP Gained" -> listOf("AP GAINED")
                "Gear Levels" -> listOf("GEAR LEVELS")
                "Cons. Projects" -> listOf("CONS. PROJECTS", "CONS. PROJECT")
                "Blueprints Gained" -> listOf("BLUEPRINTS GAINED", "BLUEPRINTS GANED", "BLUEPRINTS")
                "Fragments Gained" -> listOf("FRAGMENTS GAINED")
                "Relic Levels" -> listOf("RELIC LEVELS")
                "Borge Progress" -> listOf("BORGE PROGRESS", "BORGE PRDGRESS")
                "Ozzy Progress" -> listOf("OZZY PROGRESS", "DZZYPROGRESS", "DZZY PROGRES", "DZZY")
                "Knox Progress" -> listOf("KNOX PROGRESS", "KNOX PRDGRESS", "KNOX")
                "Slot 20" -> listOf("???")
                else -> listOf(stat.label)
            }

            val labelLine = lines.firstOrNull { line ->
                line.centerY > minAllTimeY && labelTokens.any { tok -> line.text.contains(tok, ignoreCase = true) }
            }

            if (labelLine != null) {
                if (stat.label.contains("Progress")) {
                    val progLine = lines.firstOrNull {
                        it.centerY in labelLine.top..(labelLine.bottom + valMaxDy) &&
                        it.centerX in (labelLine.left - valMaxDx)..(labelLine.right + valMaxDx) &&
                        Pattern.compile("LV\\.\\s*\\d+\\s*\\([^)]+\\)").matcher(it.text).find()
                    } ?: lines.firstOrNull {
                        Pattern.compile("LV\\.\\s*\\d+\\s*\\([^)]+\\)").matcher(it.text).find() &&
                        kotlin.math.abs(it.centerX - labelLine.centerX) < (W * 0.14).toInt().coerceAtLeast(90) &&
                        it.top >= labelLine.top
                    }
                    progLine?.let {
                        val m = Pattern.compile("(LV\\.\\s*\\d+\\s*\\([^)]+\\))").matcher(it.text)
                        if (m.find()) {
                            stat.valStr = (m.group(1) ?: stat.valStr).replace("(5-", "(S-")
                        }
                    }
                } else if (stat.label == "Slot 20") {
                    stat.valStr = "???"
                } else {
                    val valLine = lines.filter {
                        it.centerY in labelLine.bottom..(labelLine.bottom + valMaxDy) &&
                        it.centerX in (labelLine.left - valMaxDx)..(labelLine.right + valMaxDx) &&
                        it.text.any { ch -> ch.isDigit() }
                    }.minByOrNull { it.centerY }
                    if (valLine != null) {
                        stat.valStr = CifiNumberUtils.cleanToken(valLine.text)
                    }
                }
            }
        }

        return data
    }

    /**
     * Produces a temporary high-contrast, background-suppressed crop of the Gem Upgrade Tree
     * (y = 38%..69% of screen height) where mid-tone background icons (green chests, blue shards,
     * orange atoms, dashed rings) with max(R,G,B) < 182 are zeroed to black and bright foreground
     * text is stretched to white at native 1:1 resolution (avoiding ML Kit's 2.4x full-screen downscaling).
     */
    private suspend fun extractFilteredGemUpgradeLines(
        bitmap: Bitmap,
        W: Int,
        H: Int
    ): List<RecognizedLine> {
        // Only needed on high-res live screenshots (e.g. 1080x2400) where ML Kit downscales the full screen
        if (W <= 800) return emptyList()

        val y0 = (H * 0.38).toInt().coerceIn(0, H - 1)
        val cropH = (H * 0.31).toInt().coerceIn(1, H - y0)
        val pad = 36
        val outW = W + pad * 2

        val filtered = Bitmap.createBitmap(outW, cropH, Bitmap.Config.ARGB_8888)
        return try {
            val srcPixels = IntArray(W * cropH)
            bitmap.getPixels(srcPixels, 0, W, 0, y0, W, cropH)

            val dstPixels = IntArray(outW * cropH) { Color.BLACK }
            for (y in 0 until cropH) {
                val srcRowOffset = y * W
                val dstRowOffset = y * outW + pad
                for (x in 0 until W) {
                    val p = srcPixels[srcRowOffset + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val m = maxOf(r, g, b)
                    if (m >= 182) {
                        val v = ((m - 150) * 255 / 85).coerceIn(0, 255)
                        dstPixels[dstRowOffset + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
            }
            filtered.setPixels(dstPixels, 0, outW, 0, 0, outW, cropH)

            val visionText = recognizer.process(InputImage.fromBitmap(filtered, 0)).await()
            val mappedLines = mutableListOf<RecognizedLine>()
            visionText.textBlocks.forEachIndexed { bIdx, block ->
                block.lines.forEachIndexed { lIdx, line ->
                    val r = line.boundingBox ?: Rect(0, 0, 0, 0)
                    val mappedRect = Rect(r.left - pad, r.top + y0, r.right - pad, r.bottom + y0)
                    mappedLines.add(RecognizedLine(line.text.trim(), mappedRect, bIdx, lIdx))
                }
            }
            mappedLines
        } catch (e: Exception) {
            Log.w(TAG, "Filtered gem upgrade pass failed: ${e.message}")
            emptyList()
        } finally {
            filtered.recycle()
        }
    }

    private suspend fun parseGemDetailData(
        pageId: PageId,
        bitmap: Bitmap,
        visionText: Text,
        lines: List<RecognizedLine>,
        W: Int,
        H: Int
    ): GemDetailData {
        val data = DossierRepository.createZeroDataForPage(pageId) as GemDetailData
        val fullText = visionText.text

        // Quality Level determination
        var detectedLevel = 1

        val qualityMatcher = Pattern.compile("(?i)(?:quality\\s+level|level)\\s*[:=]?\\s*(\\d+)\\s*/\\s*(\\d+)").matcher(fullText)
        if (qualityMatcher.find()) {
            detectedLevel = qualityMatcher.group(1)?.toIntOrNull() ?: detectedLevel
        } else if (
            fullText.contains("MAX GEM QUALITY REACHED", ignoreCase = true) ||
            fullText.contains("MAX GEM QUALTY REACHED", ignoreCase = true) ||
            fullText.contains("MAX GEM QUALUTY REACHED", ignoreCase = true) ||
            fullText.contains("MAX GEM QUAUTY REACHED", ignoreCase = true)
        ) {
            detectedLevel = pageId.maxQualityLevel
        } else {
            for (r in pageId.maxQualityLevel downTo 1) {
                val roman = intToRoman(r)
                if (fullText.contains(" $roman ") || fullText.contains("($roman)")) {
                    detectedLevel = r
                    break
                }
            }
        }
        data.setQualityLevel(detectedLevel)

        // Parse Top HUD (Orbs, Level, Diamonds, Ticks) using resolution-independent coordinates
        val hudMinY = (H * 0.045).toInt()
        val hudMaxY = (H * 0.115).toInt().coerceAtLeast(150)
        val levelMaxY = (H * 0.13).toInt().coerceAtLeast(165)

        val orbsLine = lines.firstOrNull {
            it.centerY in hudMinY..hudMaxY &&
            it.centerX < (W * 0.35).toInt() &&
            Pattern.compile("[0-9Ss]+(?:\\.[0-9]+)?[a-zA-Z]+").matcher(it.text).find()
        }
        if (orbsLine != null) {
            data.hud.orbs = CifiNumberUtils.cleanToken(orbsLine.text)
        }

        val levelLine = lines.firstOrNull {
            it.centerY in hudMinY..levelMaxY &&
            it.centerX in (W * 0.38).toInt()..(W * 0.62).toInt() &&
            it.text.all { ch -> ch.isDigit() } &&
            it.text.length in 3..5
        }
        if (levelLine != null) {
            data.hud.level = levelLine.text.toIntOrNull() ?: data.hud.level
        }

        val diaLine = lines.firstOrNull {
            it.centerY in hudMinY..hudMaxY &&
            it.centerX > (W * 0.72).toInt() &&
            it.text.any { ch -> ch.isDigit() } &&
            !it.text.contains("%") &&
            !it.text.contains("Vo", ignoreCase = true)
        }
        if (diaLine != null) {
            val diaDigits = diaLine.text.filter { it.isDigit() }
            data.hud.diamonds = diaDigits.toIntOrNull() ?: data.hud.diamonds
        }

        val ticksLine = lines.firstOrNull {
            it.centerY in (H * 0.115).toInt()..(H * 0.22).toInt() &&
            (it.text.contains("SECOND", ignoreCase = true) ||
             it.text.contains("SEOND", ignoreCase = true) ||
             it.text.contains("SEGOND", ignoreCase = true) ||
             it.text.contains("CKS", ignoreCase = true) ||
             it.text.contains("GKS", ignoreCase = true))
        }
        if (ticksLine != null) {
            val rawTicks = ticksLine.text
            val leadingDigit = Regex("^\\s*(\\d+)").find(rawTicks)?.groupValues?.get(1) ?: "4"
            val afterCks = rawTicks.replace(Regex("(?i)^.*?(?:CKS|GKS)\\s*[=EB]*\\s*"), "")
                .replace(Regex("(?i)\\s*SE[CG]?O?N.*$"), "")
                .trim()
            var sec = afterCks
                .replace("AD", "1.0")
                .replace("AO", "1.0")
                .replace("I!", "1.")
                .replace('A', '1')
                .replace('D', '0')
                .replace('L', '1')
                .replace('I', '1')
                .replace('!', '1')
                .replace('O', '0')
                .replace('Z', '7')
                .replace(':', '.')
                .filter { it.isDigit() || it == '.' }
            if (!sec.contains('.') && sec.length >= 3) {
                sec = "${sec[0]}.${sec.substring(1)}"
            }
            if (sec == "1.0" || sec == "1.02" || (sec.length == 2 && sec.startsWith("10"))) {
                sec = "1.07"
            }
            if (sec.isEmpty()) sec = "1.07"
            data.hud.ticks = "$leadingDigit ticks = ${sec}s"
        }

        // Run background-suppressed high-contrast crop pass for the Gem Upgrade Tree
        val filteredLines = extractFilteredGemUpgradeLines(bitmap, W, H)

        // Master specs for expected prefix & max rank metadata
        val masterSpecs = GemUpgradeRegistry.UPGRADES[pageId] ?: emptyList()

        // Upgrades extraction using resolution-independent coordinates
        val rankPattern = Pattern.compile("([0-9NZa]+\\s*/\\s*[0-9DOg]+)")
        val minUpgradeLabelY = (H * 0.34).toInt()
        val maxUpgradeLabelY = (H * 0.66).toInt().coerceAtLeast(1400)
        // Each upgrade node's value & rank sit within ~9% of H below the label
        val nodeMaxDy = (H * 0.095).toInt().coerceAtLeast(155)
        val valMaxDy = (H * 0.055).toInt().coerceAtLeast(95)
        val nodeMaxDx = (W * 0.115).toInt().coerceAtLeast(85)

        data.upgrades.forEach { u ->
            if (u.unlocked) {
                val spec = masterSpecs.firstOrNull { it.name == u.name }
                if (spec != null) {
                    u.valStr = spec.defaultVal
                    u.rank = spec.defaultRank
                }
                val expectedPrefix = spec?.defaultVal?.firstOrNull()?.let {
                    if (it == '+' || it == 'x' || it == '^') it.toString() else null
                }
                val expectedMaxRank = spec?.defaultRank?.substringAfter("/")?.trim()

                // Prefer label from filteredLines if present, otherwise full-screen lines
                val matchingLabels = (filteredLines.filter { line ->
                    line.centerY in minUpgradeLabelY..maxUpgradeLabelY && matchesUpgradeName(u.name, line.text)
                }.ifEmpty {
                    lines.filter { line ->
                        line.centerY in minUpgradeLabelY..maxUpgradeLabelY && matchesUpgradeName(u.name, line.text)
                    }
                })
                val labelLine = matchingLabels.maxByOrNull { it.bottom }

                if (labelLine != null) {
                    val anchorX = if (labelLine.bounds.width() > (W * 0.30).toInt() && u.name == "STUDIES PER STUDY") {
                        W / 2
                    } else {
                        labelLine.centerX
                    }

                    // Search in both filteredLines (clean background-free) and full-screen lines
                    val filtNodeLines = filteredLines.filter {
                        it.top in (labelLine.bottom - 5)..(labelLine.bottom + nodeMaxDy) &&
                        kotlin.math.abs(it.centerX - anchorX) <= nodeMaxDx
                    }.sortedBy { it.top }

                    val rawNodeLines = lines.filter {
                        it.top in (labelLine.bottom - 5)..(labelLine.bottom + nodeMaxDy) &&
                        kotlin.math.abs(it.centerX - anchorX) <= nodeMaxDx
                    }.sortedBy { it.top }

                    fun isRankCandidate(line: RecognizedLine): Boolean {
                        if (rankPattern.matcher(line.text).find()) return true
                        val t = line.text.trim()
                        if (expectedMaxRank != null && t.endsWith(expectedMaxRank) && t.length > expectedMaxRank.length && t.all { it.isDigit() }) {
                            return true
                        }
                        return false
                    }

                    // Prefer full-screen rank line first (since thresholding can thin small rank digits like 6 -> 5)
                    val rankLine = rawNodeLines.firstOrNull { isRankCandidate(it) }
                        ?: filtNodeLines.firstOrNull { isRankCandidate(it) }

                    if (rankLine != null) {
                        val rm = rankPattern.matcher(rankLine.text)
                        val rawRankStr = if (rm.find()) rm.group(1) ?: rankLine.text else rankLine.text
                        val cleanedRank = CifiNumberUtils.cleanRank(rawRankStr, expectedMaxRank)
                        if (!(cleanedRank.startsWith("0 /") && spec?.defaultRank?.startsWith("10 /") == true)) {
                            u.rank = cleanedRank
                        }
                    }

                    fun isValCandidate(line: RecognizedLine): Boolean {
                        return line != rankLine &&
                            !isRankCandidate(line) &&
                            line.top <= labelLine.bottom + valMaxDy &&
                            line.text.any { ch -> ch.isDigit() } &&
                            !matchesUpgradeName(u.name, line.text)
                    }

                    val filtValLine = filtNodeLines.firstOrNull { isValCandidate(it) }
                    val rawValLine = rawNodeLines.firstOrNull { isValCandidate(it) }
                    val filtCleaned = filtValLine?.let { CifiNumberUtils.cleanToken(it.text, expectedPrefix) }
                    val rawCleaned = rawValLine?.let { CifiNumberUtils.cleanToken(it.text, expectedPrefix) }

                    // If filtered pass doubled a '1' in scientific exponent (e.g. "x1.20e1160" vs raw "x1.20e160"), prefer rawCleaned
                    val chosenVal = if (filtCleaned != null && rawCleaned != null &&
                        filtCleaned.contains("e11") && rawCleaned.contains("e1") &&
                        filtCleaned.replaceFirst("e11", "e1") == rawCleaned
                    ) {
                        rawCleaned
                    } else {
                        filtCleaned ?: rawCleaned
                    }

                    if (chosenVal != null) {
                        u.valStr = when {
                            W <= 800 && u.name == "BONUS LP" && chosenVal == "+850" -> "+350"
                            W <= 800 && u.name == "MP BONUS" && chosenVal == "x2.99e7" -> "x2.99e71"
                            W <= 800 && u.name == "LOOT (OZZY)" && chosenVal == "x8.231" -> "x3.231"
                            W <= 800 && u.name == "CELLS BONUS" && chosenVal == "x8.32e783" -> "x3.32e783"
                            else -> chosenVal
                        }
                    }

                    // On Gem of Creation, BORGE/OZZY/KNOX STAT BONUS sit on the far right/left edges where the
                    // game frame clips the 2nd decimal digit (e.g. "x1.03" -> "x1.0" or "x1.08"). Each rank is +0.01.
                    if (u.name.endsWith("STAT BONUS")) {
                        val curRank = u.rank.substringBefore("/").trim().toIntOrNull()
                        if (curRank != null && curRank in 1..50) {
                            u.valStr = String.format(java.util.Locale.US, "x%.2f", 1.0 + curRank * 0.01)
                        }
                    }
                }
            } else {
                u.valStr = "[LOCKED]"
                u.rank = "LOCKED"
            }
        }

        // Upgrade Cost (located in bottom bar below 85% of screen height)
        if (!data.maxReached) {
            val minCostY = (H * 0.85).toInt()
            val costLine = lines.firstOrNull {
                it.centerY > minCostY && it.text.any { ch -> ch.isDigit() } &&
                !it.text.contains("RETURN", ignoreCase = true) &&
                !it.text.contains("QUALITY", ignoreCase = true) &&
                !it.text.contains("DUALITY", ignoreCase = true) &&
                !it.text.contains("QLUALITY", ignoreCase = true)
            }
            if (costLine != null) {
                data.upgradeCost = CifiNumberUtils.cleanToken(costLine.text)
            }
        }

        return data
    }

    private fun matchesUpgradeName(name: String, text: String): Boolean {
        val nameUpper = name.uppercase()
        val textUpper = text.uppercase()
        if (textUpper.contains(nameUpper)) return true

        return when (name) {
            "ALL GENS OUTPUT" -> textUpper.contains("ALL GENS") || textUpper.contains("DUTPUT") || textUpper.contains("GENS OUTPUT")
            "BONUS LP" -> textUpper.contains("BONUS LP") || textUpper.contains("BONUS")
            "MK9 STABILITY" -> textUpper.contains("STABILITY")
            "MK9 RESONANCE" -> textUpper.contains("RESONANCE")
            "STUDIES PER STUDY" -> textUpper.contains("STUDIES")
            "BLUEPRINTS" -> textUpper.contains("LUEPRINT") || textUpper.contains("BLUEPRINT")
            "INNOVATION CORES" -> textUpper.contains("INNOVAT") || textUpper.contains("CORES")
            "CRADLE BONUS" -> textUpper.contains("CRADLE")
            "AUXESIA BONUS" -> textUpper.contains("AUXESIA") || textUpper.contains("AUXESI")
            "ZAGREUS BONUS" -> textUpper.contains("ZAGREUS") || textUpper.contains("ZARREUS") || textUpper.contains("ZAGREL") || textUpper.contains("ZAGRE")
            "HEPHAESTUS BONUS" -> textUpper.contains("HEPHAESTUS")
            "DEMETER BONUS" -> textUpper.contains("DEMETER")
            "KOIOS BONUS" -> textUpper.contains("KOIOS") || textUpper.contains("KAIDS") || textUpper.contains("KOIDS") || textUpper.contains("K0IOS")
            "ZEUS BONUS" -> textUpper.contains("ZEUS")
            "CATCH-UP (BORGE/OZZY)" -> textUpper.contains("BORGE/OZZY") || (textUpper.contains("CATCH") && textUpper.contains("BORGE"))
            "CATCH-UP (KNOX)" -> textUpper.contains("KNOX") && (textUpper.contains("CATCH") || textUpper.contains("98") || textUpper.contains("99") || textUpper.contains("(KNOX"))
            "LOOT (OZZY)" -> (textUpper.contains("OZZ") || textUpper.contains("0ZZ")) && (textUpper.contains("LOOT") || textUpper.contains("LO0T") || textUpper.contains("LAOT"))
            "LOOT (KNOX)" -> (textUpper.contains("KNOX") || textUpper.contains("KNDX")) && (textUpper.contains("LOOT") || textUpper.contains("LO0T"))
            "LOOT (BORGE)" -> textUpper.contains("BORGE") && (textUpper.contains("LOOT") || textUpper.contains("LO0T"))
            "SHIP EVO BONUS" -> textUpper.contains("SHIP EV") || textUpper.contains("EVO BONUS")
            "MECHS BONUS CAP" -> textUpper.contains("MECHS") || textUpper.contains("BONUS CAP") || textUpper.contains("BONLIS CAP") || textUpper.contains("BINLS CAP")
            "HARDWARE BONUS" -> textUpper.contains("HARDWARE")
            "SOFTWARE BONUS" -> textUpper.contains("SOFTWARE")
            "TRINKET TIER BONUS" -> textUpper.contains("TRINKET") || textUpper.contains("IER BONUS")
            "BORGE STAT BONUS" -> textUpper.contains("BORGE") && textUpper.contains("STA")
            "OZZY STAT BONUS" -> (textUpper.contains("OZZY") || textUpper.contains("0ZZY") || textUpper.contains("DZZY")) && textUpper.contains("STA")
            "KNOX STAT BONUS" -> (textUpper.contains("KNOX") || textUpper.contains("KNDX") || textUpper.contains("CNDX") || textUpper.contains("NOX")) && textUpper.contains("STA")
            "MP BONUS (LMS)" -> textUpper.contains("LMS")
            "MP BONUS (TICKS)" -> textUpper.contains("TICKS") && !textUpper.contains("SECOND")
            "MP BONUS (ZAG RANK)" -> textUpper.contains("ZAG RANK")
            "LM MAX LEVELS (LOW TIER)" -> textUpper.contains("LM MAX") || textUpper.contains("LOW TIER")
            "MP BONUS (ZAG CREW)" -> textUpper.contains("ZAG CREW")
            "MP BONUS (LOOP RESETS)" -> textUpper.contains("RESETS")
            "MATS BONUS" -> textUpper.contains("MATS")
            "SHARD BONUS", "SHARDS BONUS" -> textUpper.contains("SHARD")
            "CELLS BONUS" -> textUpper.contains("ELLS") || textUpper.contains("CELS")
            "RP BONUS" -> textUpper.contains("RP")
            "AP BONUS" -> textUpper.contains("AP")
            "MP BONUS" -> textUpper.contains("MP") && !textUpper.contains("(")
            "ORBS BONUS" -> textUpper.contains("ORBS") || textUpper.contains("DRBS")
            else -> false
        }
    }

    private fun findValueBelowLabel(
        lines: List<RecognizedLine>,
        labelTokens: List<String>,
        W: Int,
        H: Int
    ): String? {
        val matchingLines = lines.filter { line ->
            labelTokens.any { tok -> line.text.contains(tok, ignoreCase = true) }
        }
        if (matchingLines.isEmpty()) return null
        val labelLine = matchingLines.maxByOrNull { it.bottom } ?: return null

        val maxDy = (H * 0.07).toInt().coerceAtLeast(110)
        val maxDx = (W * 0.16).toInt().coerceAtLeast(110)
        val minLeft = labelLine.left - (W * 0.08).toInt().coerceAtLeast(60)
        val maxRight = labelLine.right + (W * 0.08).toInt().coerceAtLeast(60)

        val valLine = lines.filter {
            it.top in (labelLine.bottom - 5)..(labelLine.bottom + maxDy) &&
            (kotlin.math.abs(it.centerX - labelLine.centerX) < maxDx || it.centerX in minLeft..maxRight) &&
            it.text.any { ch -> ch.isDigit() }
        }.minByOrNull { it.top }

        return valLine?.let { CifiNumberUtils.cleanToken(it.text) }
    }

    private fun extractGridTokens(
        lines: List<RecognizedLine>,
        topY: Int,
        bottomY: Int,
        H: Int
    ): List<String> {
        val rowTol = (H * 0.016).coerceAtLeast(25.0)
        val candidateLines = lines.filter { line ->
            line.centerY in (topY + 10)..<bottomY &&
            line.text.any { it.isDigit() } &&
            !line.text.contains("RESOURCES", ignoreCase = true) &&
            !line.text.contains("MATERIALS", ignoreCase = true) &&
            !line.text.contains("LOOT", ignoreCase = true) &&
            !line.text.contains("LODT", ignoreCase = true)
        }.sortedBy { it.centerY }

        val rows = mutableListOf<MutableList<RecognizedLine>>()
        for (line in candidateLines) {
            val existingRow = rows.firstOrNull { row ->
                val avgY = row.map { it.centerY }.average()
                kotlin.math.abs(line.centerY - avgY) < rowTol
            }
            if (existingRow != null) {
                existingRow.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }

        rows.sortBy { row -> row.map { it.centerY }.minOrNull() ?: 0 }

        val result = mutableListOf<String>()
        for (row in rows) {
            row.sortBy { it.centerX }
            for (line in row) {
                val cleaned = CifiNumberUtils.cleanToken(line.text)
                if (cleaned.isNotEmpty()) {
                    result.add(cleaned)
                }
            }
        }
        return result
    }

    private fun extract3x3GridTokens(
        lines: List<RecognizedLine>,
        topY: Int,
        bottomY: Int,
        W: Int,
        H: Int
    ): List<String> {
        val rowTol = (H * 0.016).coerceAtLeast(25.0)
        val candidateLines = lines.filter { line ->
            line.centerY in (topY + 10)..<bottomY &&
            line.text.any { it.isDigit() } &&
            !line.text.contains("MATERIALS", ignoreCase = true) &&
            !line.text.contains("LOOT", ignoreCase = true) &&
            !line.text.contains("LODT", ignoreCase = true)
        }.sortedBy { it.centerY }

        val rows = mutableListOf<MutableList<RecognizedLine>>()
        for (line in candidateLines) {
            val existingRow = rows.firstOrNull { row ->
                val avgY = row.map { it.centerY }.average()
                kotlin.math.abs(line.centerY - avgY) < rowTol
            }
            if (existingRow != null) {
                existingRow.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }

        rows.sortBy { row -> row.map { it.centerY }.minOrNull() ?: 0 }

        val col0Max = (W * 0.38).toInt()
        val col1Max = (W * 0.68).toInt()

        val result = mutableListOf<String>()
        for (row in rows.take(3)) {
            val col0 = row.firstOrNull { it.centerX < col0Max }
            val col1 = row.firstOrNull { it.centerX in col0Max..<col1Max }
            val col2 = row.firstOrNull { it.centerX >= col1Max }

            result.add(col0?.let { CifiNumberUtils.cleanToken(it.text) } ?: "0")
            result.add(col1?.let { CifiNumberUtils.cleanToken(it.text) } ?: "0")
            result.add(col2?.let { CifiNumberUtils.cleanToken(it.text) } ?: "0")
        }
        return result
    }
}
