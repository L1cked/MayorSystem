package mayorSystem.econ

import mayorSystem.MayorPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.scheduler.BukkitRunnable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic /sell integration.
 *
 * Many sell plugins do not expose a stable API. The most universal signal is:
 * - the player runs a sell command
 * - their Vault balance increases shortly after
 *
 * We compute the balance delta and then deposit the mayor "sell multiplier" bonus.
 */
class SellBonusListener(
    private val plugin: MayorPlugin,
    private val service: SellBonusService
) : Listener {

    private data class Pending(
        val startBalance: Double,
        val startedAtMs: Long,
        val multiplier: Double,
        val maxWaitTicks: Int,
        val minDelta: Double,
        val guardEnabled: Boolean,
        val minItemDelta: Int,
        val startItemCount: Int
    )

    private val pending: MutableMap<UUID, Pending> = ConcurrentHashMap()

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(e: PlayerCommandPreprocessEvent) {
        if (!plugin.config.getBoolean("sell_bonus.enabled", true)) return
        if (!plugin.economy.isAvailable()) return

        val msg = e.message
        if (msg.isBlank() || !msg.startsWith("/")) return
        val first = msg.substring(1).trim().split(' ', limit = 2).firstOrNull()?.lowercase() ?: return

        val commands = plugin.config.getStringList("sell_bonus.commands")
            .map { it.trim().lowercase().removePrefix("/") }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("sell", "sellall") }

        if (first !in commands) return

        val p = e.player
        // If an API-based integration already handled a sell transaction for this player,
        // suppress the command fallback to avoid double-bonusing.
        if (service.isFallbackSuppressed(p.uniqueId)) return
        if (pending.containsKey(p.uniqueId)) return

        val currentTerm = plugin.termService.compute(Instant.now()).first
        val multiplier = plugin.perks.sellMultiplierForTerm(currentTerm)
        if (multiplier <= 1.0000001) return

        val startBal = plugin.economy.balance(p) ?: return

        val maxWaitTicks = plugin.config.getInt("sell_bonus.max_wait_ticks", 200).coerceAtLeast(20)
        val checkEvery = plugin.config.getInt("sell_bonus.check_interval_ticks", 10).coerceAtLeast(1)
        val minDelta = plugin.config.getDouble("sell_bonus.min_balance_delta", 0.01)
        val guardEnabled = plugin.config.getBoolean("sell_bonus.fallback_guard.enabled", true)
        val minItemDelta = plugin.config.getInt("sell_bonus.fallback_guard.min_item_delta", 1).coerceAtLeast(1)
        val startItemCount = if (guardEnabled) countInventoryItems(p) else 0
        val startedAtMs = System.currentTimeMillis()

        pending[p.uniqueId] = Pending(
            startBalance = startBal,
            startedAtMs = startedAtMs,
            multiplier = multiplier,
            maxWaitTicks = maxWaitTicks,
            minDelta = minDelta,
            guardEnabled = guardEnabled,
            minItemDelta = minItemDelta,
            startItemCount = startItemCount
        )

        object : BukkitRunnable() {
            override fun run() {
                val pend = pending[p.uniqueId]
                if (pend == null) {
                    cancel()
                    return
                }

                // If an API hook handled this sell, skip the fallback to avoid double bonus/message.
                if (service.isFallbackSuppressed(p.uniqueId)) {
                    pending.remove(p.uniqueId)
                    cancel()
                    return
                }

                if (!p.isOnline) {
                    pending.remove(p.uniqueId)
                    cancel()
                    return
                }

                val elapsedTicks = ((System.currentTimeMillis() - pend.startedAtMs) / 50L).toInt()
                if (elapsedTicks >= pend.maxWaitTicks) {
                    pending.remove(p.uniqueId)
                    cancel()
                    return
                }

                val current = plugin.economy.balance(p) ?: return
                val delta = current - pend.startBalance
                if (delta <= pend.minDelta) return

                if (pend.guardEnabled) {
                    val currentItems = countInventoryItems(p)
                    if (currentItems > pend.startItemCount - pend.minItemDelta) {
                        pending.remove(p.uniqueId)
                        cancel()
                        return
                    }
                }

                val bonus = delta * (pend.multiplier - 1.0)
                if (bonus <= pend.minDelta) {
                    pending.remove(p.uniqueId)
                    cancel()
                    return
                }

                val ok = plugin.economy.deposit(p, bonus)
                pending.remove(p.uniqueId)
                cancel()

                if (ok) {
                    service.notifySellBonus(p, bonus, pend.multiplier)
                }
            }
        }.runTaskTimer(plugin, 1L, checkEvery.toLong())
    }

    private fun countInventoryItems(player: org.bukkit.entity.Player): Int {
        val contents = player.inventory.storageContents
        var total = 0
        for (item in contents) {
            if (item == null || item.type.isAir) continue
            total += item.amount
        }
        return total
    }
}
