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

    override val title: Component = mm.deserialize("<gradient:#00c6ff:#0072ff>✨ Mayor Status</gradient>")
    override val rows: Int = 5

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val (currentTerm, electionTerm) = plugin.termService.computeCached(now)
        val safeElectionTerm = if (electionTerm < 0) 0 else electionTerm
        val times = plugin.termService.timesFor(safeElectionTerm)

        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

        // Current mayor + perks
        val currentMayorUuid = if (currentTerm >= 0) plugin.store.winner(currentTerm) else null
        val currentMayorName = currentMayorUuid?.let { plugin.server.getOfflinePlayer(it).name } ?: "None"
        val currentMayorPerks = if (currentTerm >= 0 && currentMayorUuid != null) {
            plugin.store.chosenPerks(currentTerm, currentMayorUuid)
                .map { plugin.perks.displayNameFor(currentTerm, it) }
        } else emptyList()

        // Election open (respect admin override)
        val override = plugin.config.getString("admin.election_override.$safeElectionTerm")?.uppercase()
        val electionOpen = when (override) {
            "OPEN" -> true
            "CLOSED" -> false
            else -> plugin.termService.isElectionOpen(now, safeElectionTerm)
        }

        // Timeline
        inv.setItem(
            13,
            icon(
                Material.CLOCK,
                "<gold>🧭 Timeline</gold>",
                listOf(
                    "<gray>Current term:</gray> <white>${if (currentTerm < 0) "Not started" else "#${currentTerm + 1}"}</white>",
                    "<gray>Next term:</gray> <white>#${safeElectionTerm + 1}</white>",
                    "",
                    "<gray>Next term starts:</gray> <white>${fmt.format(times.termStart)}</white>",
                    "<gray>Voting opens:</gray> <white>${fmt.format(times.electionOpen)}</white>",
                    "<gray>Voting closes:</gray> <white>${fmt.format(times.electionClose)}</white>",
                    if (override != null) "<yellow>Admin override:</yellow> <white>$override</white>" else "<dark_gray>(no overrides)</dark_gray>"
                )
            )
        )

        // Current mayor card
        val mayorLore = mutableListOf<String>()
        mayorLore += "<gray>Name:</gray> <white>$currentMayorName</white>"
        if (currentMayorPerks.isNotEmpty()) {
            mayorLore += ""
            mayorLore += "<gray>Active perks:</gray>"
            currentMayorPerks.take(6).forEach { name -> mayorLore += "<gray>•</gray> $name" }
            if (currentMayorPerks.size > 6) mayorLore += "<dark_gray>+${currentMayorPerks.size - 6} more…</dark_gray>"
        } else {
            mayorLore += "<dark_gray>No perks selected (or no mayor yet).</dark_gray>"
        }

        inv.setItem(
            21,
            icon(
                Material.GOLDEN_HELMET,
                "<gradient:#f7971e:#ffd200>👑 Current Mayor</gradient>",
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

        // Election leaderboard (only meaningful when election is open)
        val leaderLore = mutableListOf<String>()
        if (electionOpen) {
            leaderLore += "<gray>Election is LIVE for term:</gray> <white>#${safeElectionTerm + 1}</white>"
            leaderLore += "<dark_gray>(top 3 by votes)</dark_gray>"
            leaderLore += ""

            val top = plugin.store.topCandidates(safeElectionTerm, limit = 3, includeRemoved = false)
            if (top.isEmpty()) {
                leaderLore += "<gray>No candidates yet.</gray>"
            } else {
                top.forEachIndexed { i, (entry, votes) ->
                    leaderLore += "<gray>#${i + 1}</gray> <white>${entry.lastKnownName}</white> <dark_gray>-</dark_gray> <gold>$votes</gold>"
                }
            }
        } else {
            leaderLore += "<gray>Election is not open right now.</gray>"
            leaderLore += "<dark_gray>Come back during the vote window.</dark_gray>"
        }

        inv.setItem(
            23,
            icon(
                Material.PAPER,
                "<gradient:#00d2ff:#3a7bd5>🗳 Election</gradient>",
                leaderLore
            )
        )

        // Back
        inv.setItem(36, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(36, inv.getItem(36)!!) { p -> plugin.gui.open(p, MainMenu(plugin)) }
    }
}
