package mayorSystem.platform.paper

import mayorSystem.MayorPlugin
import mayorSystem.api.MayorPerkDefinition
import mayorSystem.api.MayorPerkSection
import mayorSystem.api.MayorPerkSource
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.Plugin

class ExternalPerkSectionSeeder(
    private val plugin: MayorPlugin,
    private val services: MayorServices
) {

    fun seedExternalPerkSectionsIfMissing(): Boolean {
        if (!services.hasAddonPerkSources()) return false
        val sources = listOfNotNull(economySource(), skyblockSource())
        val changed = services.addonPerkSources.seedSources(sources)
        if (changed) {
            plugin.saveConfig()
            sources.forEach { source ->
                plugin.logger.info("Synced ${source.displayName} perks into config.yml.")
            }
        }
        return changed
    }

    fun isExternalPerkPlugin(name: String): Boolean {
        return when (name) {
            "SystemSellAddon",
            "SystemSkyblockStyleAddon",
            "SystemSkyblockStyleSystem",
            "SystemSkyblockStyle" -> true
            else -> false
        }
    }

    private fun economySource(): MayorPerkSource? {
        val sell = plugin.server.pluginManager.getPlugin("SystemSellAddon") ?: return null
        if (!sell.isEnabled) return null

        val sellCfg = addonConfig(sell) ?: return null

        val perksSec = sellCfg.getConfigurationSection("mayor-perks") ?: return null
        return StaticMayorPerkSource(
            id = "systemselladdon",
            displayName = "SystemSellAddon",
            sections = listOf(
                sectionFromConfig(
                    id = "economy",
                    pickLimit = 0,
                    displayName = "<gradient:#f7971e:#ffd200>Economy</gradient>",
                    icon = "GOLD_INGOT",
                    sourceConfig = sellCfg,
                    perkIds = perksSec.getKeys(false),
                    sourcePath = { perkId -> "mayor-perks.$perkId" }
                )
            )
        )
    }

    private fun skyblockSource(): MayorPerkSource? {
        val addon = findSkyblockStyleAddon() ?: return null
        if (!addon.isEnabled) return null

        val addonCfg = addonConfig(addon) ?: return null

        val perksSec = addonCfg.getConfigurationSection("perks") ?: return null
        return StaticMayorPerkSource(
            id = addon.name.lowercase().filter { it.isLetterOrDigit() }.take(64).ifBlank { "skyblockstyle" },
            displayName = addon.name,
            sections = listOf(
                sectionFromConfig(
                    id = "skyblock_style",
                    pickLimit = 2,
                    displayName = "<gradient:#2c3e50:#4ca1af>Skyblock Style</gradient>",
                    icon = "DIAMOND_PICKAXE",
                    sourceConfig = addonCfg,
                    perkIds = perksSec.getKeys(false),
                    sourcePath = { perkId -> "perks.$perkId.meta" }
                )
            )
        )
    }

    private fun addonConfig(plugin: Plugin): FileConfiguration? {
        return runCatching {
            plugin.javaClass.getMethod("getConfig").invoke(plugin) as? FileConfiguration
        }.getOrNull()
    }

    private fun sectionFromConfig(
        id: String,
        pickLimit: Int,
        displayName: String,
        icon: String,
        sourceConfig: FileConfiguration,
        perkIds: Set<String>,
        sourcePath: (String) -> String,
    ): MayorPerkSection = MayorPerkSection(
        id = id,
        enabled = true,
        pickLimit = pickLimit,
        displayName = displayName,
        icon = icon,
        perks = perkIds.map { perkId ->
            val src = sourcePath(perkId)
            MayorPerkDefinition(
                id = perkId,
                enabled = sourceConfig.getBoolean("$src.enabled", true),
                displayName = sourceConfig.getString("$src.display_name") ?: "<white>$perkId</white>",
                icon = sourceConfig.getString("$src.icon") ?: "CHEST",
                lore = sourceConfig.getStringList("$src.lore"),
                adminLore = sourceConfig.getStringList("$src.admin_lore"),
                onStart = emptyList(),
                onEnd = emptyList()
            )
        }
    )

    private fun findSkyblockStyleAddon(): Plugin? {
        val pm = plugin.server.pluginManager
        val names = listOf(
            "SystemSkyblockStyleAddon",
            "SystemSkyblockStyleSystem",
            "SystemSkyblockStyle"
        )
        for (name in names) {
            val p = pm.getPlugin(name) ?: continue
            if (p.isEnabled) return p
        }

        val normalizedTargets = setOf(
            "systemskyblockstyleaddon",
            "systemskyblockstylesystem",
            "systemskyblockstyle"
        )
        val acceptedPrefixes = setOf(
            "systemskyblockstyleaddon",
            "systemskyblockstylesystem"
        )
        for (p in pm.plugins) {
            if (!p.isEnabled) continue
            val norm = p.name.lowercase().filter { it.isLetterOrDigit() }
            if (norm in normalizedTargets || acceptedPrefixes.any { norm.startsWith(it) }) {
                return p
            }
        }
        return null
    }

    private data class StaticMayorPerkSource(
        override val id: String,
        override val displayName: String,
        private val sections: List<MayorPerkSection>
    ) : MayorPerkSource {
        override val available: Boolean = true

        override fun sections(): List<MayorPerkSection> = sections
    }
}
