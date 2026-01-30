package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant
import java.util.UUID

/**
 * Confirmation menu for candidates who want to step down.
 */
class StepDownConfirmMenu(
    plugin: MayorPlugin,
    private val term: Int,
    private val candidate: UUID
) : Menu(plugin) {

    override val title: Component = mm.deserialize("<red>Confirm Step Down</red>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        if (!plugin.settings.stepdownEnabled) {
            inv.setItem(13, icon(Material.BARRIER, "<red>Step down is disabled</red>"))
            val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
            inv.setItem(18, back)
            set(18, back) { p, _ -> plugin.gui.open(p, CandidateMenu(plugin)) }
            return
        }

        val entry = plugin.store.candidateEntry(term, candidate)
        val status = entry?.status ?: CandidateStatus.REMOVED
        val votes = plugin.store.voteCounts(term)[candidate] ?: 0
        val bioRaw = plugin.store.candidateBio(term, candidate).trim()
        val chosen = plugin.store.chosenPerks(term, candidate).toList()

        val lore = buildList {
            add("<gray>Term:</gray> <white>#${term + 1}</white>")
            add("<gray>Status:</gray> <white>${status.name}</white>")
            add("<gray>Votes:</gray> <white>$votes</white>")
            add("")
            if (bioRaw.isBlank()) {
                add("<gray>Bio:</gray> <dark_gray>(none)</dark_gray>")
            } else {
                add("<gray>Bio:</gray>")
                val lines = wrapLore(bioRaw, 32)
                lines.take(4).forEach { add("<white>$it</white>") }
                if (lines.size > 4) add("<dark_gray>+ more...</dark_gray>")
            }

            add("")
            if (chosen.isEmpty()) {
                add("<gray>Perks:</gray> <dark_gray>(none)</dark_gray>")
            } else {
                add("<gray>Perks:</gray>")
                chosen.take(6).forEach { perkId ->
                    val name = plugin.perks.displayNameFor(term, perkId)
                    add("<gray>•</gray> $name")
                }
                if (chosen.size > 6) add("<dark_gray>+ ${chosen.size - 6} more...</dark_gray>")
            }

            if (plugin.settings.applyCost > 0.0) {
                add("")
                add("<red>You won't get your money back.</red>")
            }
        }

        val head = selfHead(player, "<gold>${player.name}</gold>", lore)
        inv.setItem(13, head)

        val cancel = icon(Material.RED_DYE, "<red>Cancel</red>", listOf("<gray>Keep your application.</gray>"))
        inv.setItem(11, cancel)
        setDeny(11, cancel) { p, _ -> plugin.gui.open(p, CandidateMenu(plugin)) }

        val confirm = icon(Material.LIME_DYE, "<green>Confirm</green>", listOf("<gray>Withdraw from the election.</gray>"))
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p, _ ->
            val now = Instant.now()
            val electionTerm = plugin.termService.computeCached(now).second
            if (!plugin.settings.stepdownEnabled) {
                plugin.messages.msg(p, "public.stepdown_disabled")
                plugin.gui.open(p, CandidateMenu(plugin))
                return@setConfirm
            }
            if (!plugin.termService.isElectionOpen(now, electionTerm)) {
                plugin.messages.msg(p, "public.stepdown_closed")
                plugin.gui.open(p, CandidateMenu(plugin))
                return@setConfirm
            }

            val current = plugin.store.candidateEntry(electionTerm, candidate)
            if (current == null || current.status == CandidateStatus.REMOVED) {
                plugin.messages.msg(p, "public.stepdown_not_candidate")
                plugin.gui.open(p, CandidateMenu(plugin))
                return@setConfirm
            }

            plugin.store.setCandidateStepdown(electionTerm, candidate)
            plugin.messages.msg(p, "public.stepdown_done")
            plugin.gui.open(p, CandidateMenu(plugin))
        }
    }
}
