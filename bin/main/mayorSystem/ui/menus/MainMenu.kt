package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

class MainMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#00c6ff:#0072ff>🏛 Mayor</gradient> <gray>Menu</gray>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val (currentTerm, electionTerm) = plugin.termService.computeCached(now)

        // Election window is for the NEXT term (electionTerm)
        val electionOpen = plugin.termService.isElectionOpen(now, electionTerm)

        // Current mayor is the winner of the CURRENT term (currentTerm)
        val mayorName = if (currentTerm >= 0) {
            plugin.store.winner(currentTerm)
                ?.let { uuid ->
                    plugin.store.winnerName(currentTerm)
                        ?: plugin.server.getOfflinePlayer(uuid).name
                }
                ?: "None"
        } else {
            "None"
        }

        val currentTermLine = if (currentTerm >= 0) {
            "<gray>Current term:</gray> <white>#${currentTerm + 1}</white>"
        } else {
            "<gray>Current term:</gray> <white>Not started</white>"
        }

        // Header / quick info (clickable -> opens Status)
        inv.setItem(
            4,
            icon(
                Material.NETHER_STAR,
                "<gold>🏛 Mayor System</gold>",
                listOf(
                    currentTermLine,
                    "<gray>Mayor:</gray> <white>$mayorName</white>",
                    if (electionOpen) "<green>Election is OPEN</green>" else "<red>Election is CLOSED</red>",
                    "",
                    "<gray>Click to view:</gray> <white>Status & Timeline</white>",
                    "<dark_gray>Use the buttons below.</dark_gray>"
                )
            )
        )
        set(4, inv.getItem(4)!!) { p -> plugin.gui.open(p, StatusMenu(plugin)) }

        // Vote
        if (player.hasPermission("mayor.vote")) {
            inv.setItem(
                20,
                icon(
                    Material.PAPER,
                    "<aqua>🗳 Vote</aqua>",
                    listOf(
                        if (electionOpen) "<green>Voting is OPEN</green>" else "<red>Voting is CLOSED</red>",
                        "<gray>Pick a candidate.</gray>"
                    )
                )
            )
            set(20, inv.getItem(20)!!) { p -> plugin.gui.open(p, VoteMenu(plugin)) }
        }

        // Apply
        if (player.hasPermission("mayor.apply")) {
            inv.setItem(
                22,
                icon(
                    Material.WRITABLE_BOOK,
                    "<yellow>📜 Apply</yellow>",
                    listOf("<gray>Run for the next term.</gray>")
                )
            )
            set(22, inv.getItem(22)!!) { p -> p.performCommand("mayor apply") }
        }

        // Candidate panel (always openable)
        val isCandidate = plugin.store.isCandidate(electionTerm, player.uniqueId)
        val candidateTitle = "<gradient:#ff512f:#dd2476>👑 Candidate</gradient>"
        if (isCandidate) {
            val item = selfHead(
                player,
                candidateTitle,
                listOf(
                    "<gray>Open your candidate dashboard.</gray>",
                    "<dark_gray>Term #${electionTerm + 1}</dark_gray>"
                )
            )
            inv.setItem(24, item)
            set(24, item) { p -> plugin.gui.open(p, CandidateMenu(plugin)) }
        } else {
            val item = selfHead(
                player,
                candidateTitle,
                listOf(
                    "<red>Not applied yet.</red>",
                    "<gray>Open to apply and manage perks.</gray>"
                )
            )
            inv.setItem(24, item)
            set(24, item) { p -> plugin.gui.open(p, CandidateMenu(plugin)) }
        }
        // Admin / Staff panel
        if (player.hasPermission(Perms.ADMIN_PANEL_OPEN) || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)) {
            inv.setItem(49, icon(Material.REDSTONE, "<red>🛡 Admin Panel</red>", listOf("<gray>Staff tools.</gray>")))
            set(49, inv.getItem(49)!!) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
        }
    }
}
