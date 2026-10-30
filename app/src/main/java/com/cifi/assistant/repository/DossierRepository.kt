package com.cifi.assistant.repository

import com.cifi.assistant.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DossierRepository {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val bank: MutableMap<PageId, PageRecord> = mutableMapOf()
    private val listeners: MutableList<() -> Unit> = mutableListOf()

    init {
        resetAll()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }

    fun resetAll() {
        bank.clear()
        PageId.entries.forEach { pageId ->
            bank[pageId] = PageRecord(
                pageId = pageId.key,
                timestamp = "---",
                uploaded = false,
                matchedTitle = null,
                data = createZeroDataForPage(pageId)
            )
        }
        notifyListeners()
    }

    fun getRecord(pageId: PageId): PageRecord {
        return bank[pageId] ?: PageRecord(
            pageId = pageId.key,
            timestamp = "---",
            uploaded = false,
            matchedTitle = null,
            data = createZeroDataForPage(pageId)
        )
    }

    fun getAllRecords(): Map<PageId, PageRecord> = bank

    fun getStoredCount(): Int {
        return bank.values.count { it.uploaded }
    }

    fun isPageUploaded(pageId: PageId): Boolean {
        return bank[pageId]?.uploaded == true
    }

    fun savePage(pageId: PageId, data: PageData, matchedTitle: String?) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val record = bank[pageId] ?: PageRecord(
            pageId = pageId.key,
            timestamp = timeStr,
            uploaded = true,
            matchedTitle = matchedTitle,
            data = data
        )

        record.timestamp = timeStr
        record.uploaded = true
        record.matchedTitle = matchedTitle
        record.data = data
        bank[pageId] = record

        // If a gem page was updated, synchronize its Roman level to Profile Inventory
        if (data is GemDetailData) {
            syncToProfileInventory(pageId, data.romanLevel)
        } else if (data is ProfileInventoryData) {
            // Backfill any already-captured Gem levels into the newly saved Inventory page
            bank.forEach { (id, rec) ->
                val gemData = rec.data as? GemDetailData
                if (rec.uploaded && gemData != null) {
                    syncToProfileInventory(id, gemData.romanLevel)
                }
            }
        }

        notifyListeners()
    }

    private fun syncToProfileInventory(gemPageId: PageId, romanLevel: String) {
        val invRecord = bank[PageId.PROFILE_INVENTORY] ?: return
        val invData = invRecord.data as? ProfileInventoryData ?: return

        when (gemPageId) {
            PageId.GEM_EXODUS -> invData.exodusGemLevel = romanLevel
            PageId.GEM_EVO -> invData.outerGems["evo"]?.level = romanLevel
            PageId.GEM_TEMP -> invData.outerGems["temp"]?.level = romanLevel
            PageId.GEM_INNO -> invData.outerGems["inno"]?.level = romanLevel
            PageId.GEM_PWR -> invData.outerGems["pwr"]?.level = romanLevel
            PageId.GEM_ATTR -> invData.outerGems["attr"]?.level = romanLevel
            PageId.GEM_CREAT -> invData.outerGems["creat"]?.level = romanLevel
            else -> {}
        }
    }

    fun createZeroDataForPage(pageId: PageId): PageData {
        return when (pageId) {
            PageId.PROFILE_INVENTORY -> ProfileInventoryData()
            PageId.PROFILE_STATS -> ProfileStatsData()
            else -> {
                val defs = GemUpgradeRegistry.UPGRADES[pageId] ?: emptyList()
                GemDetailData(
                    pageIdKey = pageId.key,
                    currentLevel = 0,
                    maxLevel = pageId.maxQualityLevel,
                    qualityLevel = "0 / ${pageId.maxQualityLevel}",
                    romanLevel = "---",
                    maxReached = false,
                    upgradeCost = "---",
                    hud = HudData(),
                    upgrades = defs.map { def ->
                        GemUpgradeItem(
                            name = def.name,
                            reqLevel = def.reqLevel,
                            unlocked = false,
                            valStr = "x0.00",
                            rank = "0 / 0"
                        )
                    }.toMutableList()
                )
            }
        }
    }

    fun compileDossierString(): String {
        val sb = StringBuilder()
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())

        sb.append("========================================================\n")
        sb.append("   CIFI COMPREHENSIVE MULTI-PAGE DOSSIER\n")
        sb.append("   Total Pages in Bank: 9 | Generated: $dateStr\n")
        sb.append("========================================================\n\n")

        sb.append("SUMMARY: ${getStoredCount()} of 9 pages captured.\n\n")

        PageId.entries.forEach { pageId ->
            val record = bank[pageId]
            sb.append(">>> [PAGE: ${pageId.displayName.uppercase()}] <<<\n")
            if (record != null && record.uploaded) {
                sb.append("Status: CAPTURED & EXTRACTED\n")
                sb.append("Timestamp: ${record.timestamp}\n")
                if (!record.matchedTitle.isNullOrEmpty()) {
                    sb.append("Matched Title: ${record.matchedTitle}\n")
                }
                sb.append(record.data.toFormattedDossier())
            } else {
                sb.append("Status: NOT CAPTURED (ALL ZEROES)\n")
            }
            sb.append("\n--------------------------------------------------------\n\n")
        }

        return sb.toString()
    }

    fun compileDossierJson(): String {
        val root = buildJsonObject {
            val dateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            put("generatedAt", kotlinx.serialization.json.JsonPrimitive(dateStr))
            put("pagesCaptured", kotlinx.serialization.json.JsonPrimitive(getStoredCount()))
            put("totalPages", kotlinx.serialization.json.JsonPrimitive(9))

            PageId.entries.forEach { pageId ->
                val record = bank[pageId]
                val pageObj = buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(pageId.key))
                    put("name", kotlinx.serialization.json.JsonPrimitive(pageId.displayName))
                    put("uploaded", kotlinx.serialization.json.JsonPrimitive(record?.uploaded == true))
                    put("timestamp", kotlinx.serialization.json.JsonPrimitive(record?.timestamp ?: "---"))
                    put("matchedTitle", kotlinx.serialization.json.JsonPrimitive(record?.matchedTitle ?: ""))

                    val dataElement = when (val d = record?.data) {
                        is ProfileInventoryData -> json.encodeToJsonElement(d)
                        is ProfileStatsData -> json.encodeToJsonElement(d)
                        is GemDetailData -> {
                            val clean = CleanGemExport(
                                currentLevel = d.currentLevel,
                                romanLevel = d.romanLevel,
                                maxReached = d.maxReached,
                                upgradeCost = if (d.maxReached) "MAX" else d.upgradeCost,
                                hud = d.hud,
                                upgrades = d.upgrades.filter { it.unlocked }.map {
                                    CleanUpgradeItem(it.name, it.valStr, it.rank)
                                }
                            )
                            json.encodeToJsonElement(clean)
                        }
                        null -> kotlinx.serialization.json.JsonNull
                    }
                    put("data", dataElement)
                }
                put(pageId.key, pageObj)
            }
        }
        return json.encodeToString(root)
    }
}
