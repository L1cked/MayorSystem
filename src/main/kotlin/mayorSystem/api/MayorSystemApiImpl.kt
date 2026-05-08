package mayorSystem.api

import mayorSystem.MayorPlugin
import mayorSystem.config.Settings
import mayorSystem.config.SystemGateOption
import mayorSystem.data.RequestStatus
import mayorSystem.data.MayorStore
import mayorSystem.elections.TermService
import mayorSystem.perks.PerkService
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration

internal interface MayorSystemApiDependencies {
    val config: FileConfiguration
    val settings: Settings
    val store: MayorStore
    val perks: PerkService
    val hasTermService: Boolean
    val termService: TermService
}

private class MayorPluginApiDependencies(private val plugin: MayorPlugin) : MayorSystemApiDependencies {
    override val config: FileConfiguration get() = plugin.config
    override val settings: Settings get() = plugin.settings
    override val store: MayorStore get() = plugin.store
    override val perks: PerkService get() = plugin.perks
    override val hasTermService: Boolean get() = plugin.hasTermService()
    override val termService: TermService get() = plugin.termService
}

class MayorSystemApiImpl internal constructor(private val deps: MayorSystemApiDependencies) : MayorSystemApi {

    constructor(plugin: MayorPlugin) : this(MayorPluginApiDependencies(plugin))

    override fun currentTermOrNull(): Int? {
        if (!deps.hasTermService) return null
        if (deps.settings.isBlocked(SystemGateOption.PERKS)) return null
        val term = deps.termService.computeNow().first
        return if (term < 0) null else term
    }

    override fun activePerkIdsOrEmpty(): Set<String> {
        val term = currentTermOrNull() ?: return emptySet()
        return activePerkIdsForTerm(term)
    }

    override fun activePerkIdsForTerm(term: Int): Set<String> {
        if (term < 0) return emptySet()
        if (deps.settings.isBlocked(SystemGateOption.PERKS)) return emptySet()
        if (!deps.config.getBoolean("perks.enabled", true)) return emptySet()

        val mayor = deps.store.winner(term) ?: return emptySet()
        val chosen = deps.store.chosenPerks(term, mayor)
        if (chosen.isEmpty()) return emptySet()

        val preset = deps.perks.presetPerks()
        val requestsById = deps.store.listRequests(term).associateBy { it.id }

        val out = linkedSetOf<String>()
        for (perkId in chosen) {
            if (perkId.startsWith("custom:", ignoreCase = true)) {
                val reqId = perkId.substringAfter(':').toIntOrNull() ?: continue
                val req = requestsById[reqId] ?: continue
                if (req.status != RequestStatus.APPROVED) continue
                out += "custom:$reqId"
            } else if (preset.containsKey(perkId)) {
                out += perkId
            }
        }
        return out
    }

    override fun isPerkActive(perkId: String): Boolean = activePerkIdsOrEmpty().contains(perkId)

    override fun perkConfigSection(perkId: String): ConfigurationSection? {
        if (perkId.startsWith("custom:", ignoreCase = true)) return null
        val sections = deps.config.getConfigurationSection("perks.sections") ?: return null
        for (sectionId in sections.getKeys(false)) {
            val perksSec = deps.config.getConfigurationSection("perks.sections.$sectionId.perks") ?: continue
            if (!perksSec.contains(perkId)) continue
            return perksSec.getConfigurationSection(perkId)
        }
        return null
    }

    override fun allPerkIds(): Set<String> {
        val out = linkedSetOf<String>()
        val sections = deps.config.getConfigurationSection("perks.sections")
        if (sections != null) {
            for (sectionId in sections.getKeys(false)) {
                val perksSec = deps.config.getConfigurationSection("perks.sections.$sectionId.perks") ?: continue
                out += perksSec.getKeys(false)
            }
        }

        out += deps.perks.presetPerks().keys
        return out
    }
}
