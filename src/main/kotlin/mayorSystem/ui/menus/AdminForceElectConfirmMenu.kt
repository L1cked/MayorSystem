package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

class AdminForceElectConfirmMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff512f:#f09819>Force Elect</gradient> <gray>Confirm</gray>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val session = AdminForceElectFlow.get(player.uniqueId)
        if (session == null) {
            plugin.gui.open(player, AdminForceElectMenu(plugin))
            return
        }

        val term = session.termIndex
        val allowed = plugin.settings.perksAllowed(term)
        val chosen = session.chosenPerks

        val perkNames = chosen.map { plugin.perks.displayNameFor(term, it) }

        val summaryLore = buildList {
            add("<gray>Target:</gray> <white>${session.targetName}</white>")
            add("<gray>Term:</gray> <white>#${term + 1}</white>")
            add("<gray>Selected:</gray> <white>${chosen.size}/$allowed</white>")
            add("")
            if (perkNames.isEmpty()) {
                add("<red>No perks selected yet.</red>")
                add("<gray>Go back and pick perks.</gray>")
            } else {
                add("<gray>Perks:</gray>")
                perkNames.take(10).forEach { n -> add("<gray>•</gray> $n") }
                if (perkNames.size > 10) add("<dark_gray>+${perkNames.size - 10} more...</dark_gray>")
            }
            add("")
            add("<dark_gray>Force-elect ignores apply requirements and cost.</dark_gray>")
        }

        inv.setItem(13, icon(Material.BOOK, "<gold>Review force-elect</gold>", summaryLore))

        // Back to sections (navigation click)
        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>", listOf("<gray>Adjust perks.</gray>"))
        inv.setItem(18, back)
        set(18, back) { p, _ -> plugin.gui.open(p, AdminForceElectSectionsMenu(plugin)) }

        // Cancel (left) / Confirm (right) — consistent confirmation layout
        val cancel = icon(Material.RED_DYE, "<red>Cancel</red>", listOf("<gray>Discard changes.</gray>"))
        inv.setItem(11, cancel)
        setDeny(11, cancel) { p, _ ->
            AdminForceElectFlow.clear(p.uniqueId)
            plugin.gui.open(p, AdminForceElectMenu(plugin))
        }

        val confirm = icon(
            Material.LIME_DYE,
            "<green>Confirm</green>",
            listOf(
                "<gray>This will:</gray>",
                "<gray>•</gray> <white>Set perks</white>",
                "<gray>•</gray> <white>Elect immediately</white>",
                "",
                "<yellow>Double-check the perks above.</yellow>"
            )
        )
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p -> confirmForceElect(p) }
    }

    private fun confirmForceElect(admin: Player) {
        val session = AdminForceElectFlow.get(admin.uniqueId) ?: run {
            plugin.gui.open(admin, AdminForceElectMenu(plugin))
            return
        }

        val now = Instant.now()
        val electionTerm = plugin.termService.compute(now).second
        if (electionTerm != session.termIndex) {
            deny(admin, "Election term changed. Please try again.")
            AdminForceElectFlow.clear(admin.uniqueId)
            plugin.gui.open(admin, AdminForceElectMenu(plugin))
            return
        }

        val allowed = plugin.settings.perksAllowed(session.termIndex)
        if (session.chosenPerks.size != allowed) {
            deny(admin, "You must select exactly $allowed perks before force-electing.")
            plugin.gui.open(admin, AdminForceElectSectionsMenu(plugin))
            return
        }

        val name = session.targetName.ifBlank { "Unknown" }
        val ok = plugin.adminActions.forceElectNowWithPerks(
            admin,
            session.termIndex,
            session.target,
            name,
            session.chosenPerks
        )
        AdminForceElectFlow.clear(admin.uniqueId)
        if (ok) admin.sendMessage("Force-elected $name and started the new term.") else deny(admin, "Failed to force-elect.")
        plugin.gui.open(admin, AdminElectionMenu(plugin))
    }
}
