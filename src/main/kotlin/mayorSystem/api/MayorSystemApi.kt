package mayorSystem.api

import mayorSystem.api.snapshot.MayorSnapshot
import mayorSystem.api.snapshot.MayorSystemSnapshot
import mayorSystem.api.snapshot.TermSnapshot
import org.bukkit.plugin.Plugin

interface MayorSystemApi {
    fun snapshot(): MayorSystemSnapshot
    fun currentMayor(): MayorSnapshot?
    fun currentTerm(): TermSnapshot?
    fun electionTerm(): TermSnapshot?
    fun activePerkIds(): Set<String>
    fun allPerkIds(): Set<String>
    fun registerPerkSource(owner: Plugin, source: MayorPerkSource): MayorAddonRegistration
}
