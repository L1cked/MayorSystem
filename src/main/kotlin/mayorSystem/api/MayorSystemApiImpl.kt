package mayorSystem.api

import mayorSystem.MayorPlugin
import mayorSystem.config.SystemGateOption
import mayorSystem.data.RequestStatus
import org.bukkit.configuration.ConfigurationSection

class MayorSystemApiImpl(private val plugin: MayorPlugin) : MayorSystemApi {

    override fun currentTermOrNull(): Int? {
        if (!plugin.hasTermService()) return null
        if (plugin.settings.isBlocked(SystemGateOption.PERKS)) return null
        val term = plugin.termService.computeNow().first
        return if (term < 0) null else term
    }

    override fun activePerkIdsOrEmpty(): Set<String> {
        val term = currentTermOrNull() ?: return emptySet()
        return activePerkIdsForTerm(term)
    }

    override fun activePerkIdsForTerm(term: Int): Set<String> {
        if (term < 0) return emptySet()
        if (plugin.settings.isBlocked(SystemGateOption.PERKS)) return emptySet()
        if (!plugin.config.getBoolean("perks.enabled", true)) return emptySet()

        val mayor = plugin.store.winner(term) ?: return emptySet()
        val chosen = plugin.store.chosenPerks(term, mayor)
        if (chosen.isEmpty()) return emptySet()

        val preset = plugin.perks.presetPerks()
        val requestsById = plugin.store.listRequests(term).associateBy { it.id }

        val out = linkedSetOf<String>()
        for (perkId in chosen) {
            if (perkId.startsWith("custom:", ignoreCase = true)) {
                val reqId = perkId.substringAfter("custom:").toIntOrNull() ?: continue
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
        val sections = plugin.config.getConfigurationSection("perks.sections") ?: return null
        for (sectionId in sections.getKeys(false)) {
            val perksSec = plugin.config.getConfigurationSection("perks.sections.$sectionId.perks") ?: continue
            if (!perksSec.contains(perkId)) continue
            return perksSec.getConfigurationSection(perkId)
        }
        return null
    }

    override fun allPerkIds(): Set<String> {
        val out = linkedSetOf<String>()
        val sections = plugin.config.getConfigurationSection("perks.sections")
        if (sections != null) {
            for (sectionId in sections.getKeys(false)) {
                val perksSec = plugin.config.getConfigurationSection("perks.sections.$sectionId.perks") ?: continue
                out += perksSec.getKeys(false)
            }
        }

        out += plugin.perks.presetPerks().keys
        return out
    }
}
