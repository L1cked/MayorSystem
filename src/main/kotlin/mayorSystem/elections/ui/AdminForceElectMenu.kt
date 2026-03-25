package mayorSystem.elections.ui

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import mayorSystem.service.OfflinePlayerCache

/**
 * Admin UI to force-elect a mayor.
 *
 * Design goals:
 * - Default to ONLINE players (fast, safe for big servers).
 * - Optional OFFLINE search is available, but clearly labeled.
 * - Filters are simple + UI-only (no chat prompts).
 */
class AdminForceElectMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff512f:#f09819>👑 Force Elect</gradient>")
    override val rows: Int = 6

    data class State(
        var page: Int = 0,
        var startsWith: Char? = null,
        var includeOffline: Boolean = false
    )

    companion object {
        private val states = ConcurrentHashMap<UUID, State>()
        private const val PAGE_SIZE = 28

        private val GRID_SLOTS = intArrayOf(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        )

        private val LETTERS: List<Char?> = listOf(null) + ('A'..'Z').toList()
    }

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val st = states.computeIfAbsent(player.uniqueId) { State() }

        // Current election term index (the term we're electing a mayor for)
        val electionTerm = plugin.termService.compute(java.time.Instant.now()).second

        // ---------------------------------------------------------------------
        // Controls header
        // ---------------------------------------------------------------------

        val filterLabel = st.startsWith?.toString() ?: "ALL"
        val modeLabel = if (st.includeOffline) "ONLINE + OFFLINE" else "ONLINE"
        val offlineSnapshot = if (st.includeOffline) plugin.offlinePlayers.snapshot(forceRefresh = true) else null
        val loadingOffline = st.includeOffline &&
            offlineSnapshot != null &&
            offlineSnapshot.entries.isEmpty() &&
            offlineSnapshot.refreshing

        val headerLore = buildList {
            add("<gray>Target term:</gray> <white>#${electionTerm + 1}</white>")
            add("<gray>Mode:</gray> <white>$modeLabel</white>")
            add("<gray>Filter:</gray> <white>$filterLabel</white>")
            if (loadingOffline) {
                add("")
                add("<yellow>Loading offline players...</yellow>")
            }
            add("")
            add("<gray>Left-click a head:</gray> <white>Select perks & elect now</white>")
            add("<gray>Right-click a head:</gray> <white>Select perks & set forced mayor</white>")
            add("<dark_gray>(forced mayor won't start term yet)</dark_gray>")
        }
        inv.setItem(4, icon(Material.PAPER, "<gold>Pick a player</gold>", headerLore))

        // Toggle online/offline
        val modeItem = icon(
            if (st.includeOffline) Material.ENDER_EYE else Material.ENDER_PEARL,
            "<light_purple>Mode: $modeLabel</light_purple>",
            listOf(
                "<gray>Click to toggle offline listing.</gray>",
                "<dark_gray>Offline mode may be heavy on very big servers.</dark_gray>"
            )
        )
        inv.setItem(46, modeItem)
        set(46, modeItem) { p, _ ->
            st.includeOffline = !st.includeOffline
            st.page = 0
            plugin.gui.open(p, AdminForceElectMenu(plugin))
        }

        // Letter filter
        val filterItem = icon(
            Material.NAME_TAG,
            "<aqua>Filter: $filterLabel</aqua>",
            listOf(
                "<gray>Left click:</gray> <white>Next letter</white>",
                "<gray>Right click:</gray> <white>Previous letter</white>",
                "<dark_gray>(ALL = no filter)</dark_gray>"
            )
        )
        inv.setItem(47, filterItem)
        set(47, filterItem) { p, click ->
            val curIdx = LETTERS.indexOf(st.startsWith).coerceAtLeast(0)
            val nextIdx = when (click) {
                ClickType.RIGHT, ClickType.SHIFT_RIGHT -> (curIdx - 1).let { if (it < 0) LETTERS.size - 1 else it }
                else -> (curIdx + 1) % LETTERS.size
            }
            st.startsWith = LETTERS[nextIdx]
            st.page = 0
            plugin.gui.open(p, AdminForceElectMenu(plugin))
        }

        // Clear forced mayor for this term
        val clearForced = icon(
            Material.MILK_BUCKET,
            "<gray>Clear forced mayor</gray>",
            listOf("<gray>Clears forced mayor for term</gray> <white>#${electionTerm + 1}</white>")
        )
        inv.setItem(48, clearForced)
        setConfirm(48, clearForced) { p, _ ->
            plugin.adminActions.clearForcedMayor(p, electionTerm)
            plugin.messages.msg(p, "admin.election.forced_mayor_cleared", mapOf("term" to (electionTerm + 1).toString()))
            plugin.gui.open(p, AdminForceElectMenu(plugin))
        }

        // ---------------------------------------------------------------------
        // Player list
        // ---------------------------------------------------------------------

        val entries = loadPlayers(st.includeOffline, offlineSnapshot)
            .filter { it.isOnline || it.hasPlayedBefore }
            .mapNotNull { entry ->
                val name = entry.name
                if (name.isBlank()) return@mapNotNull null
                entry.uuid to name
            }
            .filter { (_, name) ->
                st.startsWith == null || name.uppercase().startsWith(st.startsWith!!.toString())
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        val pages = (entries.size + PAGE_SIZE - 1) / PAGE_SIZE
        if (st.page >= pages) st.page = 0
        val start = st.page * PAGE_SIZE
        val slice = entries.drop(start).take(PAGE_SIZE)

        // Fill grid
        for (i in GRID_SLOTS.indices) {
            val slot = GRID_SLOTS[i]
            inv.setItem(slot, null)
        }

        slice.forEachIndexed { idx, (uuid, name) ->
            val offline = Bukkit.getOfflinePlayer(uuid)
            val head = playerHead(offline, "<white>$name</white>", listOf(
                "<gray>Left-click:</gray> <white>Select perks & elect now</white>",
                "<gray>Right-click:</gray> <white>Select perks & set forced mayor</white>",
                if (offline.isOnline) "<green>Online</green>" else "<gray>Offline</gray>"
            ))
            val slot = GRID_SLOTS[idx]
            inv.setItem(slot, head)
            set(slot, head) { admin, click ->
                when (click) {
                    ClickType.RIGHT, ClickType.SHIFT_RIGHT -> {
                        val availableIds = plugin.perks.availablePerksForCandidate(electionTerm, uuid)
                            .map { it.id }
                            .toSet()
                        val preselected = if (plugin.store.isCandidate(electionTerm, uuid)) {
                            plugin.store.chosenPerks(electionTerm, uuid).filter { it in availableIds }.toSet()
                        } else {
                            emptySet()
                        }
                        AdminForceElectFlow.start(
                            admin.uniqueId,
                            electionTerm,
                            uuid,
                            name,
                            preselected,
                            AdminForceElectFlow.Mode.SET_FORCED
                        )
                        plugin.gui.open(admin, AdminForceElectSectionsMenu(plugin))
                    }
                    else -> {
                        val availableIds = plugin.perks.availablePerksForCandidate(electionTerm, uuid)
                            .map { it.id }
                            .toSet()
                        val preselected = if (plugin.store.isCandidate(electionTerm, uuid)) {
                            plugin.store.chosenPerks(electionTerm, uuid).filter { it in availableIds }.toSet()
                        } else {
                            emptySet()
                        }
                        AdminForceElectFlow.start(
                            admin.uniqueId,
                            electionTerm,
                            uuid,
                            name,
                            preselected,
                            AdminForceElectFlow.Mode.ELECT_NOW
                        )
                        plugin.gui.open(admin, AdminForceElectSectionsMenu(plugin))
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // Paging + back
        // ---------------------------------------------------------------------

        inv.setItem(
            46,
            icon(Material.ARROW, "<gray>⬅ Prev</gray>", listOf("<gray>Page:</gray> <white>${st.page + 1}/${maxOf(pages, 1)}</white>"))
        )
        set(46, inv.getItem(46)!!) { p, _ ->
            if (pages > 0) st.page = (st.page - 1).coerceAtLeast(0)
            plugin.gui.open(p, AdminForceElectMenu(plugin))
        }

        inv.setItem(
            53,
            icon(Material.ARROW, "<gray>Next ➡</gray>", listOf("<gray>Page:</gray> <white>${st.page + 1}/${maxOf(pages, 1)}</white>"))
        )
        set(53, inv.getItem(53)!!) { p, _ ->
            if (pages > 0) st.page = (st.page + 1).coerceAtMost(maxOf(pages - 1, 0))
            plugin.gui.open(p, AdminForceElectMenu(plugin))
        }

        inv.setItem(45, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(45, inv.getItem(45)!!) { p, _ -> plugin.gui.open(p, AdminElectionMenu(plugin)) }
    }

    private fun loadPlayers(
        includeOffline: Boolean,
        snapshot: OfflinePlayerCache.Snapshot?
    ): List<OfflinePlayerCache.Entry> {
        val online = Bukkit.getOnlinePlayers().map {
            OfflinePlayerCache.Entry(it.uniqueId, it.name, hasPlayedBefore = true, isOnline = true)
        }

        if (!includeOffline) return online

        val merged = LinkedHashMap<UUID, OfflinePlayerCache.Entry>(online.size * 2)
        for (entry in online) {
            merged[entry.uuid] = entry
        }
        for (entry in snapshot?.entries ?: emptyList()) {
            if (!merged.containsKey(entry.uuid)) {
                merged[entry.uuid] = entry
            }
        }
        return merged.values.toList()
    }

}

