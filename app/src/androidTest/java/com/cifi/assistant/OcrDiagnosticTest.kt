package com.cifi.assistant

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cifi.assistant.engine.OpticalAnalysisEngine
import com.cifi.assistant.model.*
import com.cifi.assistant.repository.DossierRepository
import com.cifi.assistant.util.DebugScanStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrDiagnosticTest {

    private val TAG = "OcrDiag"

    @Before
    fun setup() {
        DossierRepository.resetAll()
    }

    @Test
    fun testAllSampleScreenshots() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val testScreens = listOf(
            "Evolution.jpg" to PageId.GEM_EVO,
            "Innovation.jpg" to PageId.GEM_INNO,
            "Inventory.jpg" to PageId.PROFILE_INVENTORY,
            "Stats.jpg" to PageId.PROFILE_STATS,
            "Exodus.jpg" to PageId.GEM_EXODUS,
            "Temporality.jpg" to PageId.GEM_TEMP,
            "Power.jpg" to PageId.GEM_PWR,
            "Attraction.jpg" to PageId.GEM_ATTR,
            "Creation.jpg" to PageId.GEM_CREAT
        )

        for ((fileName, expectedPage) in testScreens) {
            val inputStream = context.assets.open(fileName)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val detectedPage = OpticalAnalysisEngine.processScreenshot(bitmap)
            assertEquals("Classification for $fileName", expectedPage, detectedPage)

            val pageRecord = DossierRepository.getRecord(detectedPage)
            assertTrue("Page $detectedPage should be marked uploaded", pageRecord.uploaded)

            Log.i(TAG, "[$fileName -> ${detectedPage.displayName}] Dossier:\n${pageRecord.data.toFormattedDossier()}")
        }

        // Verify full compiled dossier
        val fullReport = DossierRepository.compileDossierString()
        Log.i(TAG, "FULL 9-PAGE DOSSIER:\n$fullReport")
        assertEquals(9, DossierRepository.getStoredCount())
        assertTrue(fullReport.contains("SUMMARY: 9 of 9 pages captured."))
        assertTrue(fullReport.contains("* EVO: Level I"))
        assertTrue(fullReport.contains("* INNO: Level III"))
        assertTrue(fullReport.contains("Behlium       : 552.60sx"))
        assertTrue(fullReport.contains("Darkseid      : 2.52e602"))
        assertTrue(fullReport.contains("Shards Gained         : 1.51e7572"))
        assertTrue(fullReport.contains("RP Gained             : 6.88e6766"))
        assertTrue(fullReport.contains("Knox Progress         : LV. 49 (S-168)"))
        assertTrue(fullReport.contains("* DEMETER BONUS             : x663.97o"))
        assertTrue(fullReport.contains("* CELLS BONUS               : x1.01e121      (10 / 50)"))
    }

    @Test
    fun testLiveCapturedDebugScreenshots() = runBlocking {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        DebugScanStorage.init(targetContext)
        val debugDir = targetContext.getExternalFilesDir("debug_scans") ?: return@runBlocking
        if (!debugDir.exists()) return@runBlocking

        val pngFiles = PageId.entries.mapNotNull { pageId ->
            val f = File(debugDir, "${pageId.key}_screenshot.png")
            if (f.exists()) pageId to f else null
        }
        if (pngFiles.isEmpty()) return@runBlocking

        DossierRepository.resetAll()
        for ((expectedPage, file) in pngFiles) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            val detected = OpticalAnalysisEngine.processScreenshot(bitmap)
            assertEquals("Live screenshot classification for ${file.name}", expectedPage, detected)
            bitmap.recycle()
        }

        val liveDossier = DossierRepository.compileDossierString()
        Log.i(TAG, "LIVE PHONE 9-PAGE DOSSIER:\n$liveDossier")
    }
}
