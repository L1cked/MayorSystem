package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

class MainMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = gc("menus.main.title")
    override val rows: Int = 5

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val (currentTerm, electionTerm) = plugin.termService.computeCached(now)

        val electionOpen = plugin.voteAccess.isElectionOpen(now, electionTerm)

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
            g("menus.main.header.lore.current_term_active", mapOf("term" to (currentTerm + 1).toString()))
        } else {
            g("menus.main.header.lore.current_term_not_started")
        }

        inv.setItem(
            4,
            icon(
                Material.NETHER_STAR,
                g("menus.main.header.name"),
                listOf(
                    currentTermLine,
                    g("menus.main.header.lore.mayor", mapOf("mayor" to mayorName)),
                    if (electionOpen) g("menus.main.header.lore.election_open") else g("menus.main.header.lore.election_closed"),
                    "",
                    g("menus.main.header.lore.click_view"),
                    g("menus.main.header.lore.hint")
                )
            )
        )
        set(4, inv.getItem(4)!!) { p -> plugin.gui.open(p, StatusMenu(plugin)) }

        if (player.hasPermission("mayor.vote")) {
            inv.setItem(
                20,
                icon(
                    Material.PAPER,
                    g("menus.main.vote.name"),
                    listOf(
                        if (electionOpen) g("menus.main.vote.lore.open") else g("menus.main.vote.lore.closed"),
                        g("menus.main.vote.lore.pick")
                    )
                )
            )
            set(20, inv.getItem(20)!!) { p, _ ->
                if (!requireNotBlocked(p, mayorSystem.config.SystemGateOption.ACTIONS)) return@set
                val denial = plugin.voteAccess.voteAccessDenial(electionTerm, p.uniqueId, Instant.now())
                if (denial != null) {
                    denyMsg(p, denial.messageKey, denial.placeholders)
                    return@set
                }
                plugin.gui.open(p, VoteMenu(plugin))
            }
        }

        if (player.hasPermission("mayor.apply")) {
            inv.setItem(22, icon(Material.WRITABLE_BOOK, g("menus.main.apply.name"), listOf(g("menus.main.apply.lore"))))
            set(22, inv.getItem(22)!!) { p, _ ->
                if (!requireNotBlocked(p, mayorSystem.config.SystemGateOption.ACTIONS)) return@set
                if (!electionOpen) {
                    denyMsg(p, "public.apply_closed")
                    return@set
                }
                val minTicks = plugin.settings.applyPlaytimeMinutes * 60 * 20
                val playTicks = p.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE)
                if (playTicks < minTicks) {
                    denyMsg(p, "public.apply_playtime", mapOf("minutes" to plugin.settings.applyPlaytimeMinutes.toString()))
                    return@set
                }
                plugin.gui.open(p, ApplySectionsMenu(plugin))
            }
        }

        val candidateEntry = plugin.store.candidateEntry(electionTerm, player.uniqueId)
        val isCandidate = candidateEntry != null && candidateEntry.status != CandidateStatus.REMOVED
        val candidateTitle = g("menus.main.candidate.name")
        if (player.hasPermission(Perms.CANDIDATE)) {
            if (isCandidate) {
                val item = selfHead(
                    player,
                    candidateTitle,
                    listOf(
                        g("menus.main.candidate.lore.open"),
                        g("menus.main.candidate.lore.term", mapOf("term" to (electionTerm + 1).toString()))
                    )
                )
                inv.setItem(24, item)
                set(24, item) { p, _ -> plugin.gui.open(p, CandidateMenu(plugin)) }
            } else {
                val item = selfHead(
                    player,
                    candidateTitle,
                    listOf(
                        g("menus.main.candidate.lore.not_applied"),
                        g("menus.main.candidate.lore.apply_hint")
                    )
                )
                inv.setItem(24, item)
                set(24, item) { p, _ -> plugin.gui.open(p, CandidateMenu(plugin)) }
            }
        } else {
            val item = selfHead(
                player,
                candidateTitle,
                listOf(
                    g("menus.main.candidate.lore.no_permission"),
                    g("menus.main.candidate.lore.ask_admin")
                )
            )
            inv.setItem(24, item)
            setDeny(24, item) { p, _ -> denyMsg(p, "errors.no_permission") }
        }

        if (Perms.canOpenAdminPanel(player)) {
            inv.setItem(40, icon(Material.REDSTONE, g("menus.main.staff.name"), listOf(g("menus.main.staff.lore"))))
            set(40, inv.getItem(40)!!) { p, _ -> plugin.gui.open(p, AdminMenu(plugin)) }
        }
    }
}
