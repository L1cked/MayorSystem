package mayorSystem.elections.ui

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant
import kotlinx.coroutines.launch

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
        val modeLabel = if (session.mode == AdminForceElectFlow.Mode.SET_FORCED) "SET FORCED" else "ELECT NOW"

        val perkNames = chosen.map { plugin.perks.displayNameFor(term, it, player) }

        val summaryLore = buildList {
            add("<gray>Target:</gray> <white>${session.targetName}</white>")
            add("<gray>Term:</gray> <white>#${term + 1}</white>")
            add("<gray>Mode:</gray> <white>$modeLabel</white>")
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
            if (session.mode == AdminForceElectFlow.Mode.SET_FORCED) {
                add("<dark_gray>Forced mayor won't start the term yet.</dark_gray>")
            }
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

        val confirmLore = if (session.mode == AdminForceElectFlow.Mode.SET_FORCED) {
            listOf(
                "<gray>This will:</gray>",
                "<gray>-</gray> <white>Set perks</white>",
                "<gray>-</gray> <white>Set forced mayor</white>",
                "<gray>-</gray> <white>Apply when term starts</white>",
                "",
                "<yellow>Double-check the perks above.</yellow>"
            )
        } else {
            listOf(
                "<gray>This will:</gray>",
                "<gray>-</gray> <white>Set perks</white>",
                "<gray>-</gray> <white>Elect immediately</white>",
                "",
                "<yellow>Double-check the perks above.</yellow>"
            )
        }
        val confirm = icon(
            Material.LIME_DYE,
            "<green>Confirm</green>",
            confirmLore
        )
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p -> confirmForceElect(p) }
    }

    private fun confirmForceElect(admin: Player) {
        val session = AdminForceElectFlow.get(admin.uniqueId) ?: run {
            plugin.gui.open(admin, AdminForceElectMenu(plugin))
            return
        }

        val blocked = blockedReason(mayorSystem.config.SystemGateOption.SCHEDULE)
        if (blocked != null) {
            denyMm(admin, blocked)
            return
        }

        val now = Instant.now()
        val electionTerm = plugin.termService.computeCached(now).second
        if (electionTerm != session.termIndex) {
            denyMsg(admin, "admin.election.term_changed")
            AdminForceElectFlow.clear(admin.uniqueId)
            plugin.gui.open(admin, AdminForceElectMenu(plugin))
            return
        }

        val allowed = plugin.settings.perksAllowed(session.termIndex)
        if (session.chosenPerks.size != allowed) {
            denyMsg(
                admin,
                "admin.perks.perk_exact",
                mapOf("limit" to allowed.toString(), "selected" to session.chosenPerks.size.toString())
            )
            plugin.gui.open(admin, AdminForceElectSectionsMenu(plugin))
            return
        }

        val violations = plugin.perks.sectionLimitViolations(session.chosenPerks)
        if (violations.isNotEmpty()) {
            val summary = violations.joinToString(", ") { "${it.first} (${it.second})" }
            denyMsg(admin, "admin.perks.section_limit_violation", mapOf("sections" to summary))
            plugin.gui.open(admin, AdminForceElectSectionsMenu(plugin))
            return
        }

        val name = session.targetName.ifBlank { "Unknown" }
        plugin.scope.launch(plugin.mainDispatcher) {
            val ok = if (session.mode == AdminForceElectFlow.Mode.SET_FORCED) {
                plugin.adminActions.setForcedMayorWithPerks(
                    admin,
                    session.termIndex,
                    session.target,
                    name,
                    session.chosenPerks
                )
            } else {
                plugin.adminActions.forceElectNowWithPerks(
                    admin,
                    session.termIndex,
                    session.target,
                    name,
                    session.chosenPerks
                )
            }
            AdminForceElectFlow.clear(admin.uniqueId)
            if (ok) {
                if (session.mode == AdminForceElectFlow.Mode.SET_FORCED) {
                    admin.sendMessage("Forced mayor set for term #${session.termIndex + 1}: $name")
                } else {
                    admin.sendMessage("Force-elected $name and started the new term.")
                }
            } else {
                denyMsg(admin, "admin.election.force_failed")
            }
            plugin.gui.open(admin, AdminElectionMenu(plugin))
        }
    }
}

