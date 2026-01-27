package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

class AdminElectionMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff512f:#f09819>🗳 Admin Elections</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val (currentTerm, electionTerm) = plugin.termService.compute(now)

        // Safety: before term #1 starts, we still allow admin actions, but timeline can look weird.
        val times = plugin.termService.timesFor(electionTerm)
        val isOpen = plugin.termService.isElectionOpen(now, electionTerm)

        // ---------------------------------------------------------------------
        // Info panel
        // ---------------------------------------------------------------------
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

        // ---------------------------------------------------------------------
        // Toggle: start votes now / end votes now
        // ---------------------------------------------------------------------
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
            val needed = if (isOpen) Perms.ADMIN_ELECTION_END else Perms.ADMIN_ELECTION_START
            val hasPerm = admin.hasPermission(needed)
                    || admin.hasPermission(Perms.LEGACY_ADMIN_ELECTION)
                    || admin.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
            if (!hasPerm) {
                deny(admin, "You do not have permission to ${if (isOpen) "end" else "start"} elections.")
                plugin.gui.open(admin, AdminElectionMenu(plugin))
                return@setConfirm
            }
            if (isOpen) {
                val ok = plugin.adminActions.forceEndElectionNow(admin)
                if (ok) admin.sendMessage("Election ended early. New term started.") else deny(admin, "Failed to end election.")
            } else {
                val ok = plugin.adminActions.forceStartElectionNow(admin)
                if (ok) admin.sendMessage("Election started early (schedule shifted).") else deny(admin, "Failed to start election.")
            }
            plugin.gui.open(admin, AdminElectionMenu(plugin))
        }

        // ---------------------------------------------------------------------
        // Force elect menu (search/filter)
        // ---------------------------------------------------------------------
        // Use the admin's actual head skin for the icon (clean + consistent).
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
            val hasPerm = admin.hasPermission(Perms.ADMIN_ELECTION_ELECT)
                    || admin.hasPermission(Perms.LEGACY_ADMIN_ELECTION)
                    || admin.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
            if (!hasPerm) {
                deny(admin, "You do not have permission to force-elect a mayor.")
                plugin.gui.open(admin, AdminElectionMenu(plugin))
                return@set
            }
            plugin.gui.open(admin, AdminForceElectMenu(plugin))
        }

        // ---------------------------------------------------------------------
        // Clear admin overrides for this election term (useful panic button)
        // ---------------------------------------------------------------------
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
            val hasPerm = admin.hasPermission(Perms.ADMIN_ELECTION_CLEAR)
                    || admin.hasPermission(Perms.LEGACY_ADMIN_ELECTION)
                    || admin.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
            if (!hasPerm) {
                deny(admin, "You do not have permission to clear election overrides.")
                plugin.gui.open(admin, AdminElectionMenu(plugin))
                return@setConfirm
            }
            plugin.adminActions.clearAllOverridesForTerm(admin, electionTerm)
            admin.sendMessage("Cleared admin overrides for term #${electionTerm + 1}.")
            plugin.gui.open(admin, AdminElectionMenu(plugin))
        }

        // Back
        inv.setItem(49, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(49, inv.getItem(49)!!) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
    }
}
