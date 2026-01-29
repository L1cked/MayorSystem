package mayorSystem.npc.provider

import mayorSystem.MayorPlugin
import mayorSystem.npc.MayorNpcIdentity
import org.bukkit.Location
import org.bukkit.entity.Entity

/**
 * Fallback provider used when no supported NPC plugin is installed.
 *
 * We intentionally DO NOT spawn ArmorStands as a fallback: the user requested to rely only on NPC plugins
 * (Citizens/FancyNpcs/others).
 */
class DisabledMayorNpcProvider : MayorNpcProvider {

    override val id: String = "disabled"

    private lateinit var plugin: MayorPlugin

    override fun isAvailable(plugin: MayorPlugin): Boolean = true

    override fun onEnable(plugin: MayorPlugin) {
        this.plugin = plugin
    }

    override fun onDisable() {
        // no-op
    }

    override fun spawnOrMove(loc: Location, actorName: String?) {
        plugin.logger.warning("[MayorNPC] Cannot spawn Mayor NPC: no supported NPC plugin installed (Citizens/FancyNpcs).")
    }

    override fun remove() {
        // no-op
    }

    override fun updateMayor(identity: MayorNpcIdentity?) {
        // no-op
    }

    override fun isMayorNpc(entity: Entity): Boolean = false

    override fun restoreFromConfig() {
        // no-op
    }
}
