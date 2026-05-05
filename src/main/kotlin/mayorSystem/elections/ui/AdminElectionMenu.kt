package mayorSystem.elections.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant
import kotlinx.coroutines.launch

class AdminElectionMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff512f:#f09819>Admin Elections</gradient>")
    override val rows: Int = 5

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val (currentTerm, electionTerm) = plugin.termService.computeCached(now)

        val times = plugin.termService.timesFor(electionTerm)
        val isOpen = plugin.termService.isElectionOpen(now, electionTerm)
        val scheduleBlocked = blockedReason(mayorSystem.config.SystemGateOption.SCHEDULE)

        val canStart = player.hasPermission(Perms.ADMIN_ELECTION_START)
        val canEnd = player.hasPermission(Perms.ADMIN_ELECTION_END)
        val canElect = player.hasPermission(Perms.ADMIN_ELECTION_ELECT)
        val canClear = player.hasPermission(Perms.ADMIN_ELECTION_CLEAR)
        val canFakeVotes = player.hasPermission(Perms.ADMIN_ELECTION_FAKE_VOTES)

        inv.setItem(
            4,
            icon(
                Material.PAPER,
                "<gold>Election Timeline</gold>",
                listOf(
                    "<gray>Current term:</gray> <white>${if (currentTerm < 0) "Not started" else "#${currentTerm + 1}"}</white>",
                    "<gray>Election for term:</gray> <white>#${electionTerm + 1}</white>",
                    "",
                    "<gray>Election opens:</gray> <white>${timeFmt(times.electionOpen)}</white>",
                    "<gray>Election closes:</gray> <white>${timeFmt(times.electionClose)}</white>",
                    "<gray>Term starts:</gray> <white>${timeFmt(times.termStart)}</white>",
                    "<gray>Term ends:</gray> <white>${timeFmt(times.termEnd)}</white>",
                    "",
                    "<gray>Status:</gray> " + if (isOpen) "<green>OPEN</green>" else "<red>CLOSED</red>"
                )
            )
        )

        if ((isOpen && canEnd) || (!isOpen && canStart)) {
            val toggleMaterial = if (isOpen) Material.REDSTONE_TORCH else Material.LEVER
            val toggleName = if (isOpen) "<red>End Votes Now</red>" else "<green>Start Votes Now</green>"
            val toggleLore = if (isOpen) {
                listOf(
                    "<gray>Ends the voting period immediately</gray>",
                    "<gray>and starts the new term right away.</gray>",
                    "",
                    "<dark_gray>Also shifts the term schedule earlier.</dark_gray>"
                )
            } else {
                listOf(
                    "<gray>Opens the voting period immediately.</gray>",
                    "<gray>Election will last for vote_window,</gray>",
                    "<gray>then the new term starts.</gray>",
                    "",
                    "<dark_gray>Also shifts the term schedule earlier.</dark_gray>"
                )
            }

            inv.setItem(20, icon(toggleMaterial, toggleName, toggleLore))
            setConfirm(20, inv.getItem(20)!!) { admin ->
                if (scheduleBlocked != null) {
                    denyMm(admin, scheduleBlocked)
                    return@setConfirm
                }
                val hasPerm = if (isOpen) canEnd else canStart
                if (!hasPerm) {
                    denyMsg(admin, if (isOpen) "admin.election.no_permission_end" else "admin.election.no_permission_start")
                    plugin.gui.open(admin, AdminElectionMenu(plugin))
                    return@setConfirm
                }
                plugin.scope.launch(plugin.mainDispatcher) {
                    if (isOpen) {
                        dispatchResult(admin, plugin.adminActions.forceEndElectionNow(admin), denyOnNonSuccess = true)
                    } else {
                        dispatchResult(admin, plugin.adminActions.forceStartElectionNow(admin), denyOnNonSuccess = true)
                    }
                    plugin.gui.open(admin, AdminElectionMenu(plugin))
                }
            }
        }

        if (canElect) {
            inv.setItem(
                22,
                selfHead(
                    player,
                    "<gold>Force-Elect a Mayor</gold>",
                    listOf(
                        "<gray>Pick a player to instantly</gray>",
                        "<gray>start the next term with them.</gray>",
                        "",
                        "<dark_gray>Includes search + filters.</dark_gray>"
                    )
                )
            )
            set(22, inv.getItem(22)!!) { admin ->
                if (scheduleBlocked != null) {
                    denyMm(admin, scheduleBlocked)
                    return@set
                }
                if (!canElect) {
                    denyMsg(admin, "admin.election.no_permission_force")
                    plugin.gui.open(admin, AdminElectionMenu(plugin))
                    return@set
                }
                plugin.gui.open(admin, AdminForceElectMenu(plugin))
            }
        }

        if (canClear) {
            inv.setItem(
                24,
                icon(
                    Material.MILK_BUCKET,
                    "<gray>Clear Overrides</gray>",
                    listOf(
                        "<gray>Clears forced mayor and</gray>",
                        "<gray>hard open/close override for this election.</gray>",
                        "",
                        "<dark_gray>Also clears schedule overrides.</dark_gray>"
                    )
                )
            )
            setConfirm(24, inv.getItem(24)!!) { admin ->
                if (scheduleBlocked != null) {
                    denyMm(admin, scheduleBlocked)
                    return@setConfirm
                }
                if (!canClear) {
                    denyMsg(admin, "admin.election.no_permission_clear")
                    plugin.gui.open(admin, AdminElectionMenu(plugin))
                    return@setConfirm
                }
                plugin.scope.launch(plugin.mainDispatcher) {
                    dispatchResult(admin, plugin.adminActions.clearAllOverridesForTerm(admin, electionTerm), denyOnNonSuccess = true)
                    plugin.gui.open(admin, AdminElectionMenu(plugin))
                }
            }
        }

        if (canFakeVotes) {
            val item = icon(
                Material.LECTERN,
                "<gold>Fake Votes</gold>",
                listOf(
                    "<gray>Adjust fake vote totals layered</gray>",
                    "<gray>on top of real votes.</gray>",
                    "",
                    "<dark_gray>Click to manage candidate totals.</dark_gray>"
                )
            )
            inv.setItem(40, item)
            set(40, item) { admin, _ ->
                plugin.gui.open(admin, AdminFakeVotesMenu(plugin, electionTerm))
            }
        }

        inv.setItem(36, icon(Material.ARROW, "<gray><- Back</gray>"))
        set(36, inv.getItem(36)!!) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
    }
}

