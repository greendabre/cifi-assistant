package com.cifi.assistant.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface PageData {
    fun toFormattedDossier(): String
}

@Serializable
data class ResourceItem(
    val key: String,
    val label: String,
    var valStr: String = "0",
    val locked: Boolean = false
)

@Serializable
data class OuterGemData(
    var level: String = "---",
    val nodes: MutableList<Boolean> = MutableList(9) { false }
)

@Serializable
data class ProfileInventoryData(
    var player: String = "---",
    var level: Int = 0,
    var activeTab: String = "INVENTORY",
    var capturedDate: String = "---",
    var exodusGemLevel: String = "---",
    val exodusNodes: MutableList<Boolean> = MutableList(5) { false },
    val outerGems: MutableMap<String, OuterGemData> = mutableMapOf(
        "evo" to OuterGemData(),
        "temp" to OuterGemData(),
        "inno" to OuterGemData(),
        "pwr" to OuterGemData(),
        "attr" to OuterGemData(),
        "creat" to OuterGemData()
    ),
    val primaryResources: MutableList<ResourceItem> = mutableListOf(
        ResourceItem("diamonds", "Diamonds", "0"),
        ResourceItem("tokens", "Tokens", "0"),
        ResourceItem("cells", "Cells", "0"),
        ResourceItem("mps", "MPs", "0"),
        ResourceItem("shards", "Shards", "0"),
        ResourceItem("rps", "RPs", "0"),
        ResourceItem("aps", "APs", "0"),
        ResourceItem("orbs", "Orbs", "0")
    ),
    val specialResources: MutableList<ResourceItem> = mutableListOf(
        ResourceItem("cores", "Cores", "0"),
        ResourceItem("blueprints", "Blueprints", "0"),
        ResourceItem("dark_cores", "Dark Cores", "0"),
        ResourceItem("fragments", "Fragments", "0")
    ),
    val academyMaterials: MutableList<ResourceItem> = mutableListOf(
        ResourceItem("difar", "Difar", "0"),
        ResourceItem("kento", "Kento", "0"),
        ResourceItem("chromium", "Chromium", "0"),
        ResourceItem("exon", "Exon", "0"),
        ResourceItem("organium", "Organium", "0"),
        ResourceItem("adamorphium", "Adamorphium", "0"),
        ResourceItem("moskom", "Moskom", "0"),
        ResourceItem("darkseid", "Darkseid", "0"),
        ResourceItem("tokenium", "Tokenium", "0"),
        ResourceItem("un1", "Unknown 1", "[LOCKED]", locked = true),
        ResourceItem("un2", "Unknown 2", "[LOCKED]", locked = true),
        ResourceItem("un3", "Unknown 3", "[LOCKED]", locked = true),
        ResourceItem("un4", "Unknown 4", "[LOCKED]", locked = true),
        ResourceItem("un5", "Unknown 5", "[LOCKED]", locked = true),
        ResourceItem("un6", "Unknown 6", "[LOCKED]", locked = true)
    ),
    val hunterLoot: MutableList<ResourceItem> = mutableListOf(
        ResourceItem("obsidian", "Obsidian", "0"),
        ResourceItem("behlium", "Behlium", "0"),
        ResourceItem("hbm", "HBM", "0"),
        ResourceItem("farahyte", "Farahyte", "0"),
        ResourceItem("galvarium", "Galvarium", "0"),
        ResourceItem("vectid", "Vectid", "0"),
        ResourceItem("glacium", "Glacium", "0"),
        ResourceItem("quartz", "Quartz", "0"),
        ResourceItem("tesseracts", "Tesseracts", "0"),
        ResourceItem("un7", "Unknown 1", "[LOCKED]", locked = true),
        ResourceItem("un8", "Unknown 2", "[LOCKED]", locked = true),
        ResourceItem("un9", "Unknown 3", "[LOCKED]", locked = true)
    )
) : PageData {
    override fun toFormattedDossier(): String {
        val sb = StringBuilder()
        sb.append("PLAYER: $player | LEVEL: $level | TAB: $activeTab\n\n")
        sb.append("[GEM EMBLEMS & NODES]\n")
        val exLit = exodusNodes.mapIndexedNotNull { i, b -> if (b) (i + 1).toString() else null }
        sb.append("* Exodus Gem: Level $exodusGemLevel | Nodes Lit: [${if (exLit.isEmpty()) "None" else exLit.joinToString(", ")}]\n")
        for ((k, g) in outerGems) {
            val lit = g.nodes.mapIndexedNotNull { i, b -> if (b) (i + 1).toString() else null }
            sb.append("* ${k.uppercase()}: Level ${g.level} | Nodes Lit: [${if (lit.isEmpty()) "None" else lit.joinToString(", ")}]\n")
        }
        sb.append("\n[PRIMARY RESOURCES]\n")
        primaryResources.forEach { r -> sb.append("  ${r.label.padEnd(12)}: ${r.valStr}\n") }
        sb.append("\n[SPECIAL RESOURCES]\n")
        specialResources.forEach { r -> sb.append("  ${r.label.padEnd(12)}: ${r.valStr}\n") }
        sb.append("\n[ACADEMY MATERIALS]\n")
        academyMaterials.forEach { m -> sb.append("  ${m.label.padEnd(14)}: ${m.valStr}\n") }
        sb.append("\n[HUNTER LOOT]\n")
        hunterLoot.forEach { h -> sb.append("  ${h.label.padEnd(14)}: ${h.valStr}\n") }
        return sb.toString()
    }
}

@Serializable
data class OuroborosData(
    var traversals: String = "0",
    var allTimeOrbs: String = "0",
    var timeCurrent: String = "00:00:00",
    var orbsAccumCurrent: String = "0",
    var highestMeltdown: String = "0",
    var inscriptions: String = "0",
    var mechsOwned: String = "0",
    var gadgetLevels: String = "0"
)

@Serializable
data class RunCones(
    var green: Int = 0,
    var red: Int = 0,
    var blue: Int = 0,
    var orange: Int = 0
)

@Serializable
data class StatItem(
    val label: String,
    var valStr: String = "0"
)

@Serializable
data class ProfileStatsData(
    var player: String = "---",
    var level: Int = 0,
    var activeTab: String = "STATS",
    var capturedDate: String = "---",
    val ouroboros: OuroborosData = OuroborosData(),
    val currentRunCones: RunCones = RunCones(),
    val allTimeStats: MutableList<StatItem> = mutableListOf(
        StatItem("Cells Gained", "0"),
        StatItem("Manual Gens", "0"),
        StatItem("Tech Up Levels", "0"),
        StatItem("MP Gained", "0"),
        StatItem("Loop Mods", "0"),
        StatItem("Loop Resets", "0"),
        StatItem("Shards Gained", "0"),
        StatItem("Milestone Levels", "0"),
        StatItem("RP Gained", "0"),
        StatItem("Research LVs", "0"),
        StatItem("AP Gained", "0"),
        StatItem("Gear Levels", "0"),
        StatItem("Cons. Projects", "0"),
        StatItem("Blueprints Gained", "0"),
        StatItem("Fragments Gained", "0"),
        StatItem("Relic Levels", "0"),
        StatItem("Borge Progress", "LV. 0 (S-0)"),
        StatItem("Ozzy Progress", "LV. 0 (S-0)"),
        StatItem("Knox Progress", "LV. 0 (S-0)"),
        StatItem("Slot 20", "???")
    )
) : PageData {
    override fun toFormattedDossier(): String {
        val sb = StringBuilder()
        sb.append("PLAYER: $player | LEVEL: $level | TAB: $activeTab\n\n")
        sb.append("[OUROBOROS STATS]\n")
        sb.append("  ${"traversals".padEnd(20)}: ${ouroboros.traversals}\n")
        sb.append("  ${"allTimeOrbs".padEnd(20)}: ${ouroboros.allTimeOrbs}\n")
        sb.append("  ${"timeCurrent".padEnd(20)}: ${ouroboros.timeCurrent}\n")
        sb.append("  ${"orbsAccumCurrent".padEnd(20)}: ${ouroboros.orbsAccumCurrent}\n")
        sb.append("  ${"highestMeltdown".padEnd(20)}: ${ouroboros.highestMeltdown}\n")
        sb.append("  ${"inscriptions".padEnd(20)}: ${ouroboros.inscriptions}\n")
        sb.append("  ${"mechsOwned".padEnd(20)}: ${ouroboros.mechsOwned}\n")
        sb.append("  ${"gadgetLevels".padEnd(20)}: ${ouroboros.gadgetLevels}\n")
        sb.append("\n[CURRENT RUN: LEVEL POINT DISTRIBUTION]\n")
        sb.append("  Green Cone: ${currentRunCones.green} | Red Cone: ${currentRunCones.red} | Blue Cone: ${currentRunCones.blue} | Orange Cone: ${currentRunCones.orange}\n")
        sb.append("\n[ALL TIME HIGHEST STATS]\n")
        allTimeStats.forEach { s -> sb.append("  ${s.label.padEnd(22)}: ${s.valStr}\n") }
        return sb.toString()
    }
}

@Serializable
data class HudData(
    var orbs: String = "0",
    var level: Int = 0,
    var diamonds: Int = 0,
    var ticks: String = "0 ticks = 0.00s"
)

@Serializable
data class GemUpgradeItem(
    val name: String,
    val reqLevel: Int,
    var unlocked: Boolean = false,
    var valStr: String = "x0.00",
    var rank: String = "0 / 0"
)

@Serializable
data class GemDetailData(
    val pageIdKey: String,
    var currentLevel: Int = 0,
    val maxLevel: Int,
    var qualityLevel: String = "0 / $maxLevel",
    var romanLevel: String = "---",
    var maxReached: Boolean = false,
    var upgradeCost: String = "---",
    val hud: HudData = HudData(),
    val upgrades: MutableList<GemUpgradeItem> = mutableListOf()
) : PageData {

    fun setQualityLevel(level: Int) {
        currentLevel = level.coerceIn(0, maxLevel)
        romanLevel = intToRoman(currentLevel)
        qualityLevel = "$currentLevel / $maxLevel"
        maxReached = currentLevel >= maxLevel && currentLevel > 0
        upgrades.forEach { u ->
            u.unlocked = currentLevel >= u.reqLevel && currentLevel > 0
        }
    }

    override fun toFormattedDossier(): String {
        val sb = StringBuilder()
        val available = upgrades.filter { it.unlocked }
        val locked = upgrades.filter { !it.unlocked }

        sb.append("QUALITY: $qualityLevel | ROMAN: $romanLevel | MAX REACHED: $maxReached\n")
        sb.append("HUD: Orbs ${hud.orbs} | Level ${hud.level} | Diamonds ${hud.diamonds} | Ticks ${hud.ticks}\n\n")
        sb.append("[AVAILABLE GEM UPGRADES (${available.size} UNLOCKED)]\n")
        if (available.isEmpty()) {
            sb.append("  (No upgrades unlocked at current gem level)\n")
        } else {
            available.forEach { u ->
                sb.append("  * ${u.name.padEnd(26)}: ${u.valStr.padEnd(14)} (${u.rank})\n")
            }
        }
        if (locked.isNotEmpty()) {
            sb.append("\n[LOCKED UPGRADES (${locked.size} LOCKED)]\n")
            locked.forEach { u ->
                sb.append("  🔒 ${u.name.padEnd(26)}: [LOCKED - Requires Level ${u.reqLevel}]\n")
            }
        }
        if (!maxReached) {
            sb.append("\nUPGRADE COST: $upgradeCost\n")
        }
        return sb.toString()
    }
}

@Serializable
data class CleanGemExport(
    val currentLevel: Int,
    val romanLevel: String,
    val maxReached: Boolean,
    val upgradeCost: String,
    val hud: HudData,
    val upgrades: List<CleanUpgradeItem>
)

@Serializable
data class CleanUpgradeItem(
    val name: String,
    val valStr: String,
    val rank: String
)

@Serializable
data class PageRecord(
    val pageId: String,
    var timestamp: String,
    var uploaded: Boolean = false,
    var matchedTitle: String? = null,
    var data: PageData
)
