package mayorSystem.perks

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
        if (plugin.settings.isBlocked(mayorSystem.config.SystemGateOption.PERKS)) return
        plugin.perks.applyActiveEffects(e.player)
    }

    @EventHandler
    fun onRespawn(e: PlayerRespawnEvent) {
        if (plugin.settings.isBlocked(mayorSystem.config.SystemGateOption.PERKS)) return
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

        if (plugin.settings.isBlocked(mayorSystem.config.SystemGateOption.PERKS)) return

        if (!shouldReapplyMayorEffectRemoval(e.action, e.cause, e.newEffect != null)) return

        if (!plugin.perks.isActiveGlobalEffect(old.type)) return
        reapplyLater(player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onCoveredEffectAdded(e: EntityPotionEffectEvent) {
        val player = e.entity as? Player ?: return
        val incoming = e.newEffect ?: return

        if (plugin.settings.isBlocked(mayorSystem.config.SystemGateOption.PERKS)) return
        if (plugin.perks.activeGlobalEffect(e.modifiedType) == null) return
        if (plugin.perks.matchesActiveGlobalEffect(incoming)) return

        val oldWasMayor = e.oldEffect?.let { plugin.perks.matchesActiveGlobalEffect(it) } == true
        val currentIsMayor = player.getPotionEffect(e.modifiedType)?.let { plugin.perks.matchesActiveGlobalEffect(it) } == true
        val trackedMayorEffect = plugin.perks.hasAppliedGlobalEffect(player, e.modifiedType)
        if (!oldWasMayor && !currentIsMayor && !trackedMayorEffect) return
        if (!plugin.perks.shouldSuppressCoveredEffect(e.modifiedType, incoming)) return

        e.isCancelled = true
        reapplyLater(player, force = true)
    }

    private fun reapplyLater(player: Player, force: Boolean = false) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            if (plugin.settings.isBlocked(mayorSystem.config.SystemGateOption.PERKS)) return@Runnable
            plugin.perks.applyActiveEffects(player, force = force)
        })
    }
}

internal fun shouldReapplyMayorEffectRemoval(
    action: EntityPotionEffectEvent.Action,
    cause: EntityPotionEffectEvent.Cause,
    hasReplacement: Boolean
): Boolean =
    !hasReplacement &&
        (action == EntityPotionEffectEvent.Action.REMOVED ||
            action == EntityPotionEffectEvent.Action.CLEARED ||
            cause == EntityPotionEffectEvent.Cause.EXPIRATION)

