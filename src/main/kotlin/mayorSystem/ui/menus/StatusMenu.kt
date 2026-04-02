package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class StatusMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = gc("menus.status.title")
    override val rows: Int = 4

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val (currentTerm, electionTerm) = plugin.termService.computeCached(now)
        val safeElectionTerm = if (electionTerm < 0) 0 else electionTerm
        val times = plugin.termService.timesFor(safeElectionTerm)

        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

        val currentMayorUuid = if (currentTerm >= 0) plugin.store.winner(currentTerm) else null
        val currentMayorName = currentMayorUuid?.let { plugin.server.getOfflinePlayer(it).name } ?: g("menus.status.none")
        val currentMayorPerks = if (currentTerm >= 0 && currentMayorUuid != null) {
            plugin.store.chosenPerks(currentTerm, currentMayorUuid)
                .map { plugin.perks.displayNameFor(currentTerm, it, player) }
        } else emptyList()

        val override = plugin.config.getString("admin.election_override.$safeElectionTerm")?.uppercase()
        val electionOpen = when (override) {
            "OPEN" -> true
            "CLOSED" -> false
            else -> plugin.termService.isElectionOpen(now, safeElectionTerm)
        }

        inv.setItem(
            13,
            icon(
                Material.CLOCK,
                g("menus.status.timeline.name"),
                listOf(
                    g(
                        "menus.status.timeline.lore.current_term",
                        mapOf("term" to if (currentTerm < 0) g("menus.status.timeline.not_started") else "#${currentTerm + 1}")
                    ),
                    g("menus.status.timeline.lore.next_term", mapOf("term" to (safeElectionTerm + 1).toString())),
                    "",
                    g("menus.status.timeline.lore.term_start", mapOf("time" to fmt.format(times.termStart))),
                    g("menus.status.timeline.lore.voting_open", mapOf("time" to fmt.format(times.electionOpen))),
                    g("menus.status.timeline.lore.voting_close", mapOf("time" to fmt.format(times.electionClose))),
                    if (override != null) g("menus.status.timeline.lore.override", mapOf("state" to override)) else g("menus.status.timeline.lore.no_override")
                )
            )
        )

        val mayorLore = mutableListOf<String>()
        mayorLore += g("menus.status.current_mayor.lore.name", mapOf("name" to currentMayorName))
        if (currentMayorPerks.isNotEmpty()) {
            mayorLore += ""
            mayorLore += g("menus.status.current_mayor.lore.active_perks")
            currentMayorPerks.take(6).forEach { perkName ->
                mayorLore += g("menus.status.current_mayor.lore.perk_entry", mapOf("perk" to perkName))
            }
            if (currentMayorPerks.size > 6) {
                mayorLore += g("menus.status.current_mayor.lore.more", mapOf("count" to (currentMayorPerks.size - 6).toString()))
            }
        } else {
            mayorLore += g("menus.status.current_mayor.lore.none")
        }

        inv.setItem(
            21,
            icon(
                Material.GOLDEN_HELMET,
                g("menus.status.current_mayor.name"),
                mayorLore
            )
        )
        if (currentMayorUuid != null && currentTerm >= 0) {
            val item = inv.getItem(21)
            if (item != null) {
                set(21, item) { p, _ ->
                    val mayorName = plugin.store.winnerName(currentTerm) ?: plugin.server.getOfflinePlayer(currentMayorUuid).name
                    plugin.gui.open(
                        p,
                        MayorProfileMenu(
                            plugin = plugin,
                            term = currentTerm,
                            mayor = currentMayorUuid,
                            mayorName = mayorName,
                            backTo = { StatusMenu(plugin) }
                        )
                    )
                }
            }
        }

        val leaderLore = mutableListOf<String>()
        val totalRanked = plugin.store.candidates(safeElectionTerm, includeRemoved = false).size
        val canOpenRanking = electionOpen && totalRanked > 3
        if (electionOpen) {
            leaderLore += g("menus.status.election.lore.live", mapOf("term" to (safeElectionTerm + 1).toString()))
            leaderLore += g("menus.status.election.lore.top_hint")
            leaderLore += ""

            val top = plugin.store.topCandidates(safeElectionTerm, limit = 3, includeRemoved = false)
            if (top.isEmpty()) {
                leaderLore += g("menus.status.election.lore.no_candidates")
            } else {
                top.forEachIndexed { i, (entry, votes) ->
                    leaderLore += g(
                        "menus.status.election.lore.entry",
                        mapOf("rank" to (i + 1).toString(), "name" to entry.lastKnownName, "votes" to votes.toString())
                    )
                }
                if (canOpenRanking) {
                    leaderLore += g("menus.status.election.lore.click_all")
                }
            }
        } else {
            leaderLore += g("menus.status.election.lore.closed")
            leaderLore += g("menus.status.election.lore.closed_hint")
        }

        val electionItem = icon(
            Material.PAPER,
            g("menus.status.election.name"),
            leaderLore
        )
        inv.setItem(23, electionItem)
        if (canOpenRanking) {
            set(23, electionItem) { p, _ ->
                plugin.gui.open(
                    p,
                    ElectionRankingMenu(
                        plugin = plugin,
                        term = safeElectionTerm,
                        backTo = { StatusMenu(plugin) }
                    )
                )
            }
        }

        inv.setItem(27, icon(Material.ARROW, g("menus.common.back.name")))
        set(27, inv.getItem(27)!!) { p -> plugin.gui.open(p, MainMenu(plugin)) }
    }
}
