package mayorSystem.candidates.ui

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.util.UUID
import mayorSystem.service.OfflinePlayerCache

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
        val showOnlyBanned: Boolean = false,
        val page: Int = 0
    )

    override val title: Component = mm.deserialize("<gradient:#ff512f:#dd2476>🧷 Apply Bans</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val offlineSnapshot = if (state.includeOffline) plugin.offlinePlayers.snapshot(forceRefresh = true) else null
        val loadingOffline = state.includeOffline &&
            offlineSnapshot != null &&
            offlineSnapshot.entries.isEmpty() &&
            offlineSnapshot.refreshing

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
            plugin.gui.open(p, AdminApplyBanSearchMenu(plugin, state.copy(includeOffline = !state.includeOffline, page = 0)))
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
                plugin.gui.open(p, AdminApplyBanSearchMenu(plugin, state.copy(startsWith = null, page = 0)))
                return@set
            }

            val next = when (click) {
                org.bukkit.event.inventory.ClickType.LEFT -> nextLetter(state.startsWith)
                org.bukkit.event.inventory.ClickType.RIGHT -> prevLetter(state.startsWith)
                else -> state.startsWith
            }
            plugin.gui.open(p, AdminApplyBanSearchMenu(plugin, state.copy(startsWith = next, page = 0)))
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
            plugin.gui.open(p, AdminApplyBanSearchMenu(plugin, state.copy(showOnlyBanned = !state.showOnlyBanned, page = 0)))
        }

        // Back
        inv.setItem(45, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(45, inv.getItem(45)!!) { p, _ -> plugin.gui.open(p, AdminCandidatesMenu(plugin)) }

        if (loadingOffline) {
            inv.setItem(
                4,
                icon(
                    Material.CLOCK,
                    "<yellow>Loading offline players...</yellow>",
                    listOf("<gray>Try again in a moment.</gray>")
                )
            )
        }

        // Players
        val players = buildPlayerList(offlineSnapshot)
            .filter { (uuid, _) -> !state.showOnlyBanned || plugin.store.activeApplyBan(uuid) != null }
        val slots = contentSlots(inv)
        val totalPages = maxOf(1, (players.size + slots.size - 1) / slots.size)
        val page = state.page.coerceIn(0, totalPages - 1)
        val shown = players.drop(page * slots.size).take(slots.size)
        for ((index, pair) in shown.withIndex()) {
            val (uuid, name) = pair
            val slot = slots[index]

            val ban = plugin.store.activeApplyBan(uuid)

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
        }

        val prev = icon(Material.ARROW, "<gray>Prev</gray>")
        inv.setItem(48, prev)
        set(48, prev) { p, _ ->
            if (page <= 0) {
                denyClick()
            } else {
                plugin.gui.open(p, AdminApplyBanSearchMenu(plugin, state.copy(page = page - 1)))
            }
        }

        val next = icon(Material.ARROW, "<gray>Next</gray>")
        inv.setItem(52, next)
        set(52, next) { p, _ ->
            if (page >= totalPages - 1) {
                denyClick()
            } else {
                plugin.gui.open(p, AdminApplyBanSearchMenu(plugin, state.copy(page = page + 1)))
            }
        }
    }

    private fun buildPlayerList(snapshot: OfflinePlayerCache.Snapshot?): List<Pair<UUID, String>> {
        val out = linkedMapOf<UUID, String>()

        // Online first
        Bukkit.getOnlinePlayers().forEach { p ->
            out[p.uniqueId] = p.name
        }

        if (state.includeOffline) {
            for (entry in snapshot?.entries ?: emptyList()) {
                if (entry.name.isBlank()) continue
                out.putIfAbsent(entry.uuid, entry.name)
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

