package mayorSystem.npc.provider

import mayorSystem.MayorPlugin
import mayorSystem.npc.MayorNpcIdentity
import org.bukkit.Location
import org.bukkit.entity.Entity

/**
 * Pluggable backend for the "Mayor NPC".
 *
 * Implementations MUST be safe to load even when the external NPC plugin is missing.
 * (So: no hard references to Citizens/FancyNpcs classes in signatures; use reflection internally.)
 */
interface MayorNpcProvider {

    /** Short id, used in config: "citizens", "fancynpcs" (and "disabled" fallback) */
    val id: String

    /** True if the required external plugin is present/enabled (or always true for the "disabled" fallback). */
    fun isAvailable(plugin: MayorPlugin): Boolean

    /** Called when this provider becomes active (after selection). */
    fun onEnable(plugin: MayorPlugin)

    /** Called on plugin disable or when switching provider. */
    fun onDisable()

    /** Spawn (or move) the NPC to [loc]. Must persist provider-specific identifiers into config. */
    fun spawnOrMove(loc: Location, actorName: String? = null)

    /** Remove the NPC (if present) and clear provider-specific identifiers from config. */
    fun remove()

    /** Update the NPC skin + name for the current mayor, or show "No mayor". */
    fun updateMayor(identity: MayorNpcIdentity?)

    /** True if [entity] is the NPC controlled by this provider. */
    fun isMayorNpc(entity: Entity): Boolean

    /** Attempt to restore/spawn from config on startup/reload. */
    fun restoreFromConfig()
}

