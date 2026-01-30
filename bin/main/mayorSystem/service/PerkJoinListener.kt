package mayorSystem.service

import mayorSystem.MayorPlugin
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

/**
 * Ensures players keep MayorSystem potion effects:
 * - join (offline during term start)
 * - respawn after death
 * - effects cleared by external sources
 *
 * Minecraft cannot apply potion effects to offline players. So the closest "global" behavior is:
 * - Apply to everyone currently online when the term starts
 * - Re-apply on join/respawn or when removed
 */
class PerkJoinListener(private val plugin: MayorPlugin) : Listener {

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        if (!plugin.settings.enabled) {
            plugin.perks.applyActiveEffects(e.player, force = true)
            return
        }
        plugin.perks.applyActiveEffects(e.player)
    }

    @EventHandler
    fun onRespawn(e: PlayerRespawnEvent) {
        if (!plugin.settings.enabled) {
            plugin.perks.applyActiveEffects(e.player, force = true)
            return
        }
        reapplyLater(e.player)
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        // Avoid holding onto UUIDs forever.
        plugin.perks.cleanupPlayerCache(e.player.uniqueId)
    }

    @EventHandler
    fun onEffectRemoved(e: EntityPotionEffectEvent) {
        val player = e.entity as? Player ?: return
        val old = e.oldEffect ?: return

        if (!plugin.settings.enabled) return

        if (e.newEffect != null) return
        val action = e.action
        if (action != EntityPotionEffectEvent.Action.REMOVED &&
            action != EntityPotionEffectEvent.Action.CLEARED
        ) return

        if (!plugin.perks.isActiveGlobalEffect(old.type)) return
        reapplyLater(player)
    }

    private fun reapplyLater(player: Player) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!plugin.settings.enabled) {
                plugin.perks.applyActiveEffects(player, force = true)
                return@Runnable
            }
            plugin.perks.applyActiveEffects(player)
        })
    }
}
