package com.cifi.assistant.model

enum class PageId(
    val key: String,
    val displayName: String,
    val shortName: String,
    val icon: String,
    val maxQualityLevel: Int = 1,
    val subtitle: String = "",
    val accentColor: String = "#22D3EE"
) {
    PROFILE_INVENTORY(
        key = "profile_inventory",
        displayName = "Player Profile (Inventory)",
        shortName = "Inventory",
        icon = "🎒",
        accentColor = "#22D3EE"
    ),
    PROFILE_STATS(
        key = "profile_stats",
        displayName = "Player Profile (Stats)",
        shortName = "Stats",
        icon = "📊",
        accentColor = "#818CF8"
    ),
    GEM_EXODUS(
        key = "gem_exodus",
        displayName = "The Exodus Gem",
        shortName = "Exodus",
        icon = "🌌",
        maxQualityLevel = 5,
        subtitle = "ORIGINS & ENDINGS",
        accentColor = "#A855F7"
    ),
    GEM_TEMP(
        key = "gem_temp",
        displayName = "Gem of Temporality",
        shortName = "Temporality",
        icon = "⏳",
        maxQualityLevel = 4,
        subtitle = "AEON'S EQUILIBRIUM",
        accentColor = "#EC4899"
    ),
    GEM_INNO(
        key = "gem_inno",
        displayName = "Gem of Innovation",
        shortName = "Innovation",
        icon = "💡",
        maxQualityLevel = 3,
        subtitle = "COSMIC CREATOR",
        accentColor = "#F59E0B"
    ),
    GEM_PWR(
        key = "gem_pwr",
        displayName = "Gem of Power",
        shortName = "Power",
        icon = "⚡",
        maxQualityLevel = 3,
        subtitle = "FOUNDATION OF REALITY",
        accentColor = "#3B82F6"
    ),
    GEM_ATTR(
        key = "gem_attr",
        displayName = "Gem of Attraction",
        shortName = "Attraction",
        icon = "🧲",
        maxQualityLevel = 4,
        subtitle = "STELLAR FORCES",
        accentColor = "#06B6D4"
    ),
    GEM_CREAT(
        key = "gem_creat",
        displayName = "Gem of Creation",
        shortName = "Creation",
        icon = "🪐",
        maxQualityLevel = 4,
        subtitle = "MATTER & PHYSICALITY",
        accentColor = "#F97316"
    ),
    GEM_EVO(
        key = "gem_evo",
        displayName = "The Evolution Gem",
        shortName = "Evolution",
        icon = "🧬",
        maxQualityLevel = 1,
        subtitle = "LIFE, GROWTH & ADAPTATION",
        accentColor = "#22C55E"
    );

    companion object {
        fun fromKey(key: String): PageId? = entries.firstOrNull { it.key == key }
    }
}
