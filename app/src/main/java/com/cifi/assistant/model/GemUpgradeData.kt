package com.cifi.assistant.model

import kotlinx.serialization.Serializable

val INT_TO_ROMAN = listOf("---", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

fun intToRoman(level: Int): String {
    return if (level in 1..10) INT_TO_ROMAN[level] else "---"
}

@Serializable
data class GemUpgradeSpec(
    val name: String,
    val reqLevel: Int,
    val defaultVal: String = "x0.00",
    val defaultRank: String = "0 / 0"
)

object GemUpgradeRegistry {
    val UPGRADES: Map<PageId, List<GemUpgradeSpec>> = mapOf(
        PageId.GEM_EXODUS to listOf(
            GemUpgradeSpec("CELLS BONUS", 1, "x2.36e62", "74 / 999"),
            GemUpgradeSpec("SHARD BONUS", 2, "x1.39e39", "40 / 999"),
            GemUpgradeSpec("RP BONUS", 3, "x2.87e40", "32 / 999"),
            GemUpgradeSpec("MP BONUS", 4, "x57.26sx", "27 / 999"),
            GemUpgradeSpec("AP BONUS", 4, "x139.26k", "18 / 999"),
            GemUpgradeSpec("MATS BONUS", 5, "x69.38", "9 / 999"),
            GemUpgradeSpec("ORBS BONUS", 5, "x1.31", "2 / 999")
        ),
        PageId.GEM_TEMP to listOf(
            GemUpgradeSpec("MP BONUS (LMS)", 1, "x586.51k", "50 / 50"),
            GemUpgradeSpec("MP BONUS (TICKS)", 2, "x860.91k", "21 / 50"),
            GemUpgradeSpec("MP BONUS (ZAG RANK)", 3, "x5.85e149", "6 / 10"),
            GemUpgradeSpec("LM MAX LEVELS (LOW TIER)", 3, "+195", "13 / 100"),
            GemUpgradeSpec("MP BONUS (ZAG CREW)", 3, "x4.91e51", "17 / 30"),
            GemUpgradeSpec("MP BONUS (LOOP RESETS)", 3, "x1.18e105", "5 / 30")
        ),
        PageId.GEM_INNO to listOf(
            GemUpgradeSpec("STUDIES PER STUDY", 1, "+147.00", "32 / 50"),
            GemUpgradeSpec("CELLS BONUS", 2, "x1.01e121", "10 / 50"),
            GemUpgradeSpec("MP BONUS", 2, "x2.99e71", "8 / 50"),
            GemUpgradeSpec("SHARDS BONUS", 2, "x3.58e63", "7 / 50"),
            GemUpgradeSpec("RP BONUS", 2, "x2.40e55", "6 / 50"),
            GemUpgradeSpec("AP BONUS", 2, "x8.68e46", "5 / 50"),
            GemUpgradeSpec("MATS BONUS", 3, "x125.37k", "2 / 50"),
            GemUpgradeSpec("BLUEPRINTS", 3, "+291", "2 / 8"),
            GemUpgradeSpec("INNOVATION CORES", 3, "+146", "1 / 4")
        ),
        PageId.GEM_ATTR to listOf(
            GemUpgradeSpec("LOOT (BORGE)", 1, "x10.749", "27 / 50"),
            GemUpgradeSpec("LOOT (OZZY)", 2, "x3.231", "23 / 50"),
            GemUpgradeSpec("CATCH-UP (BORGE/OZZY)", 3, "x1.649", "5 / 5"),
            GemUpgradeSpec("LOOT (KNOX)", 4, "x1.259", "6 / 50"),
            GemUpgradeSpec("CATCH-UP (KNOX)", 4, "x1.222", "2 / 5"),
            GemUpgradeSpec("SHIP EVO BONUS", 4, "^1.109", "8 / 100")
        ),
        PageId.GEM_CREAT to listOf(
            GemUpgradeSpec("MECHS BONUS CAP", 1, "x3.98e145", "14 / 999"),
            GemUpgradeSpec("HARDWARE BONUS", 2, "x100.00sp", "20 / 20"),
            GemUpgradeSpec("SOFTWARE BONUS", 2, "x8.34n", "14 / 20"),
            GemUpgradeSpec("CELLS BONUS", 3, "x3.32e783", "10 / 15"),
            GemUpgradeSpec("MP BONUS", 3, "x6.20e159", "10 / 15"),
            GemUpgradeSpec("SHARDS BONUS", 3, "x6.20e159", "10 / 15"),
            GemUpgradeSpec("RP BONUS", 3, "x7.15e100", "9 / 15"),
            GemUpgradeSpec("TRINKET TIER BONUS", 4, "36.00", "9 / 80"),
            GemUpgradeSpec("BORGE STAT BONUS", 4, "x1.03", "3 / 50"),
            GemUpgradeSpec("OZZY STAT BONUS", 4, "x1.06", "6 / 50"),
            GemUpgradeSpec("KNOX STAT BONUS", 4, "x1.03", "3 / 50")
        ),
        PageId.GEM_PWR to listOf(
            GemUpgradeSpec("CRADLE BONUS", 1, "x4.32e65", "14 / 50"),
            GemUpgradeSpec("AUXESIA BONUS", 1, "x2.04e44", "11 / 50"),
            GemUpgradeSpec("ZAGREUS BONUS", 1, "x5.00e50", "10 / 50"),
            GemUpgradeSpec("HEPHAESTUS BONUS", 1, "x7.85e78", "9 / 50"),
            GemUpgradeSpec("DEMETER BONUS", 1, "x663.97o", "7 / 50"),
            GemUpgradeSpec("KOIOS BONUS", 1, "x1.47sx", "6 / 50"),
            GemUpgradeSpec("ZEUS BONUS", 1, "x7.69t", "6 / 50"),
            GemUpgradeSpec("BLUEPRINTS", 1, "+208", "13 / 50"),
            GemUpgradeSpec("INNOVATION CORES", 1, "+104", "13 / 50")
        ),
        PageId.GEM_EVO to listOf(
            GemUpgradeSpec("ALL GENS OUTPUT", 1, "x1.00e48", "6 / 50"),
            GemUpgradeSpec("BONUS LP", 1, "+350", "7 / 250"),
            GemUpgradeSpec("MK9 STABILITY", 1, "0.210", "4 / 36"),
            GemUpgradeSpec("MK9 RESONANCE", 1, "x4.61sp", "2 / 8")
        )
    )
}
