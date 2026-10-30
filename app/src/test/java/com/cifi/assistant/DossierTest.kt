package com.cifi.assistant

import com.cifi.assistant.model.*
import com.cifi.assistant.repository.DossierRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DossierTest {

    @Before
    fun setUp() {
        DossierRepository.resetAll()
    }

    @Test
    fun testInitialBankState() {
        assertEquals(0, DossierRepository.getStoredCount())
        assertEquals(9, PageId.entries.size)
        PageId.entries.forEach { pageId ->
            assertFalse(DossierRepository.isPageUploaded(pageId))
        }
    }

    @Test
    fun testRomanNumerals() {
        assertEquals("---", intToRoman(0))
        assertEquals("I", intToRoman(1))
        assertEquals("II", intToRoman(2))
        assertEquals("III", intToRoman(3))
        assertEquals("IV", intToRoman(4))
        assertEquals("V", intToRoman(5))
        assertEquals("X", intToRoman(10))
    }

    @Test
    fun testGemUpgradeUnlocks() {
        val gemExodus = DossierRepository.createZeroDataForPage(PageId.GEM_EXODUS) as GemDetailData

        // Initial level 0: all upgrades locked
        assertEquals(0, gemExodus.currentLevel)
        assertTrue(gemExodus.upgrades.all { !it.unlocked })

        // Set level 3: reqLevel <= 3 should unlock
        gemExodus.setQualityLevel(3)
        assertEquals("III", gemExodus.romanLevel)
        assertEquals("3 / 5", gemExodus.qualityLevel)

        val unlocked = gemExodus.upgrades.filter { it.unlocked }
        // Cells (1), Shard (2), RP (3) -> 3 upgrades unlocked
        assertEquals(3, unlocked.size)
        assertTrue(unlocked.any { it.name == "CELLS BONUS" })
        assertTrue(unlocked.any { it.name == "SHARD BONUS" })
        assertTrue(unlocked.any { it.name == "RP BONUS" })

        // Set level 5: all 7 upgrades unlocked and max reached
        gemExodus.setQualityLevel(5)
        assertTrue(gemExodus.maxReached)
        assertEquals(7, gemExodus.upgrades.filter { it.unlocked }.size)
    }

    @Test
    fun testDossierCompilation() {
        val invData = DossierRepository.createZeroDataForPage(PageId.PROFILE_INVENTORY) as ProfileInventoryData
        invData.player = "Commander"
        invData.level = 1500
        DossierRepository.savePage(PageId.PROFILE_INVENTORY, invData, "Player Profile (Inventory)")

        assertEquals(1, DossierRepository.getStoredCount())
        assertTrue(DossierRepository.isPageUploaded(PageId.PROFILE_INVENTORY))

        val dossierText = DossierRepository.compileDossierString()
        assertTrue(dossierText.contains("CIFI COMPREHENSIVE MULTI-PAGE DOSSIER"))
        assertTrue(dossierText.contains("SUMMARY: 1 of 9 pages captured."))
        assertTrue(dossierText.contains("PLAYER: Commander | LEVEL: 1500"))

        val json = DossierRepository.compileDossierJson()
        assertTrue(json.contains("\"pagesCaptured\": 1"))
        assertTrue(json.contains("\"player\": \"Commander\""))
    }
}
