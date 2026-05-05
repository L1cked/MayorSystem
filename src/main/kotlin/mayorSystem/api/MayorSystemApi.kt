package mayorSystem.api

import org.bukkit.configuration.ConfigurationSection

interface MayorSystemApi {
    fun currentTermOrNull(): Int?
    fun activePerkIdsOrEmpty(): Set<String>
    fun activePerkIdsForTerm(term: Int): Set<String>
    fun isPerkActive(perkId: String): Boolean
    fun perkConfigSection(perkId: String): ConfigurationSection?
    fun allPerkIds(): Set<String>
}
