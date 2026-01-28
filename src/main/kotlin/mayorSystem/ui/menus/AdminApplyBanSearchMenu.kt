package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.util.UUID

/**
 * Pick a player (online or offline) to ban/unban from applying to elections.
 *
 * Uses a simple letter filter + toggles, because full text input in an Inventory UI is pain.
 */
class AdminApplyBanSearchMenu(
    plugin: MayorPlugin,
    private val state: State = State()
) : Menu(plugin) {

    data class State(
        val includeOffline: Boolean = true,
        val startsWith: Char? = null,
        val showOnlyBanned: Boolean = false
    )

    override val title: Component = mm.deserialize("<gradient:#ff512f:#dd2476>🧷 Apply Bans</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        // Controls
        inv.setItem(
            46,
            icon(
                Material.ENDER_EYE,
                "<gold>Include offline</gold>",
                listOf("<gray>Status:</gray> <white>${if (state.includeOffline) "ON" else "OFF"}</white>")
            )
        )
        set(46, inv.getItem(46)!!) { p, _ ->
            plugin.gui.open(p, AdminApplyBanSearchMenu(plugin, state.copy(includeOffline = !state.includeOffline)))
        }

        inv.setItem(
            47,
            icon(
                Material.PAPER,
                "<aqua>Starts with</aqua>",
                listOf(
                    "<gray>Filter:</gray> <white>${state.startsWith?.toString() ?: "(any)"}</white>",
                    "<gray>Left/Right click to change.</gray>",
                    "<dark_gray>Shift-click to clear.</dark_gray>"
                )
            )
        )
        set(47, inv.getItem(47)!!) { p, click ->
            if (click.isShiftClick) {
                plugin.gui.open(p, AdminApplyBanSearchMenu(plugin, state.copy(startsWith = null)))
                return@set
            }

            val next = when (click) {
                org.bukkit.event.inventory.ClickType.LEFT -> nextLetter(state.startsWith)
                org.bukkit.event.inventory.ClickType.RIGHT -> prevLetter(state.startsWith)
                else -> state.startsWith
            }
            plugin.gui.open(p, AdminApplyBanSearchMenu(plugin, state.copy(startsWith = next)))
        }

        inv.setItem(
            53,
            icon(
                Material.NAME_TAG,
                "<light_purple>Show only banned</light_purple>",
                listOf("<gray>Status:</gray> <white>${if (state.showOnlyBanned) "ON" else "OFF"}</white>")
            )
        )
        set(53, inv.getItem(53)!!) { p, _ ->
            plugin.gui.open(p, AdminApplyBanSearchMenu(plugin, state.copy(showOnlyBanned = !state.showOnlyBanned)))
        }

        // Back
        inv.setItem(45, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(45, inv.getItem(45)!!) { p, _ -> plugin.gui.open(p, AdminCandidatesMenu(plugin)) }

        // Players
        val players = buildPlayerList()
        var slot = 10
        for ((uuid, name) in players) {
            if (slot >= 44) break

            val ban = plugin.store.activeApplyBan(uuid)
            if (state.showOnlyBanned && ban == null) continue

            val lore = mutableListOf<String>()
            if (ban == null) {
                lore += "<gray>Apply ban:</gray> <green>NONE</green>"
            } else if (ban.permanent) {
                lore += "<gray>Apply ban:</gray> <red>PERMANENT</red>"
            } else {
                val until = ban.until
                lore += "<gray>Apply ban:</gray> <gold>TEMP</gold>"
                if (until != null) lore += "<gray>Until:</gray> <white>${until.toLocalDateTime()}</white>"
            }
            lore += ""
            lore += "<gray>Click to manage ban.</gray>"

            val item = playerHead(uuid, "<yellow>$name</yellow>", lore)
            inv.setItem(slot, item)
            set(slot, item) { p, _ -> plugin.gui.open(p, AdminApplyBanTypeMenu(plugin, uuid, name)) }

            slot++
            if (slot % 9 == 8) slot++ // skip right border
        }
    }

    private fun buildPlayerList(): List<Pair<UUID, String>> {
        val out = linkedMapOf<UUID, String>()

        // Online first
        Bukkit.getOnlinePlayers().forEach { p ->
            out[p.uniqueId] = p.name
        }

        if (state.includeOffline) {
            Bukkit.getOfflinePlayers().forEach { off ->
                val name = off.name ?: return@forEach
                out.putIfAbsent(off.uniqueId, name)
            }
        }

        return out.entries
            .map { it.key to it.value }
            .filter { (_, name) ->
                val letter = state.startsWith ?: return@filter true
                name.startsWith(letter, ignoreCase = true)
            }
            .sortedBy { it.second.lowercase() }
    }

    private fun nextLetter(c: Char?): Char = when (c) {
        null -> 'A'
        'Z' -> 'A'
        else -> (c.code + 1).toChar()
    }

    private fun prevLetter(c: Char?): Char = when (c) {
        null -> 'Z'
        'A' -> 'Z'
        else -> (c.code - 1).toChar()
    }
}
