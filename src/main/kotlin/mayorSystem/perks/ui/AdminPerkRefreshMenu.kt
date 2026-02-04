package mayorSystem.perks.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import mayorSystem.perks.ui.AdminPerksMenu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

/**
 * Perk refresh UI.
 *
 * - Shows all online players (paged).
 * - Click a player to refresh their perk potion effects.
 * - Includes "Refresh all" and "Search by name" (anvil prompt).
 */
class AdminPerkRefreshMenu(
    plugin: MayorPlugin,
    private val page: Int = 0
) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#00ff88:#00b3ff>✨ Refresh Perk Effects</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val hasPerm = player.hasPermission(Perms.ADMIN_PERKS_REFRESH)
        if (!hasPerm) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have permission to refresh perk effects.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
            inv.setItem(45, back)
            set(45, back) { p -> plugin.gui.open(p, AdminPerksMenu(plugin)) }
            return
        }

        val online = Bukkit.getOnlinePlayers().sortedBy { it.name.lowercase() }
        val playerSlots = contentSlots(rows)
        val perPage = playerSlots.size
        val maxPage = if (online.isEmpty()) 0 else (online.size - 1) / perPage
        val p = page.coerceIn(0, maxPage)

        // Header / info
        inv.setItem(4, icon(Material.NETHER_STAR, "<green>Perk Refresh</green>", listOf(
            "<gray>Click a player head to refresh their perk potion effects.</gray>",
            "<dark_gray>Only affects what MayorSystem applied.</dark_gray>"
        )))

        // Player entries
        val start = p * perPage
        val end = (start + perPage).coerceAtMost(online.size)
        var i = 0
        for (idx in start until end) {
            val target = online[idx]
            val slot = playerSlots[i++]
            val head = playerHead(
                target,
                "<white>${target.name}</white>",
                listOf(
                    "<gray>Click:</gray> <white>refresh perk effects</white>"
                )
            )
            inv.setItem(slot, head)
            setConfirm(slot, head) { who ->
                plugin.adminActions.refreshPerksPlayer(who, target)
                who.sendMessage(mm.deserialize("<green>Refreshed perk effects for</green> <white>${target.name}</white><green>.</green>"))
                plugin.gui.open(who, AdminPerkRefreshMenu(plugin, p))
            }
        }

        // Controls
        val refreshAll = icon(Material.BEACON, "<green>Refresh All Online</green>", listOf(
            "<gray>Re-applies active perk potion effects</gray>",
            "<gray>for every online player.</gray>"
        ))
        inv.setItem(48, refreshAll)
        setConfirm(48, refreshAll) { who ->
            val count = plugin.adminActions.refreshPerksAll(who)
            who.sendMessage(mm.deserialize("<green>Refreshed perk effects for</green> <white>$count</white> <green>online player(s).</green>"))
            plugin.gui.open(who, AdminPerkRefreshMenu(plugin, p))
        }

        val search = icon(Material.ANVIL, "<aqua>Search Player</aqua>", listOf(
            "<gray>Type a player name to refresh.</gray>",
            "<dark_gray>(Player must be online.)</dark_gray>"
        ))
        inv.setItem(49, search)
        set(49, search) { who ->
            plugin.gui.openAnvilPrompt(
                who,
                mm.deserialize("<aqua>Refresh perks for player</aqua>"),
                "") { actor, text ->
                if (text.isNullOrBlank()) {
                    plugin.gui.open(actor, AdminPerkRefreshMenu(plugin, p))
                    return@openAnvilPrompt
                }

                val target = Bukkit.getPlayerExact(text)
                    ?: Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(text, ignoreCase = true) }

                if (target == null) {
                    actor.sendMessage(mm.deserialize("<red>Player not found online:</red> <gray>${text.replace("<", "").replace(">", "")}</gray>"))
                    plugin.gui.open(actor, AdminPerkRefreshMenu(plugin, p))
                    return@openAnvilPrompt
                }

                plugin.adminActions.refreshPerksPlayer(actor, target)
                actor.sendMessage(mm.deserialize("<green>Refreshed perk effects for</green> <white>${target.name}</white><green>.</green>"))
                plugin.gui.open(actor, AdminPerkRefreshMenu(plugin, p))
            }
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { who -> plugin.gui.open(who, AdminPerksMenu(plugin)) }

        // Paging
        val pageInfo = icon(Material.PAPER, "<gray>Page</gray>", listOf(
            "<white>${p + 1}</white><gray>/</gray><white>${maxPage + 1}</white>",
            "<dark_gray>${online.size} online</dark_gray>"
        ))
        inv.setItem(51, pageInfo)

        if (p > 0) {
            val prev = icon(Material.ARROW, "<yellow>⬅ Prev</yellow>")
            inv.setItem(46, prev)
            set(46, prev) { who -> plugin.gui.open(who, AdminPerkRefreshMenu(plugin, p - 1)) }
        }

        if (p < maxPage) {
            val next = icon(Material.ARROW, "<yellow>Next ➡</yellow>")
            inv.setItem(53, next)
            set(53, next) { who -> plugin.gui.open(who, AdminPerkRefreshMenu(plugin, p + 1)) }
        }

        // Empty state hint
        if (online.isEmpty()) {
            inv.setItem(22, icon(Material.BARRIER, "<red>No online players</red>", listOf("<gray>Nothing to refresh right now.</gray>")))
        }
    }

    private fun contentSlots(rows: Int): List<Int> {
        val slots = ArrayList<Int>()
        for (r in 1 until rows - 1) {
            for (c in 1..7) {
                slots += r * 9 + c
            }
        }
        return slots
    }
}

