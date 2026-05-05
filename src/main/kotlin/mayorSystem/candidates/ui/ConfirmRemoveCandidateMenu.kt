package mayorSystem.candidates.ui

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Tiny confirmation menu to fully remove a candidate from the election.
 *
 * Removal rules:
 * - Candidate should already be in PROCESS (enforced by AdminCandidatesMenu).
 * - Removing clears their votes (and refunds voters) via MayorStore.setCandidateStatus.
 */
class ConfirmRemoveCandidateMenu(
    plugin: MayorPlugin,
    private val term: Int,
    private val candidate: UUID
) : Menu(plugin) {

    override val title: Component = mm.deserialize("<red>Confirm Removal</red>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val entry = plugin.store.candidateEntry(term, candidate)
        val name = entry?.lastKnownName ?: "Unknown"
        val displayName = plugin.playerDisplayNames.resolve(candidate, name).mini

        inv.setItem(
            13,
            playerHead(
                candidate,
                "<red>Remove $displayName?</red>",
                listOf(
                    "<gray>This will:</gray>",
                    "<white>• Mark them as REMOVED</white>",
                    "<white>• Refund all votes</white>",
                    "",
                    "<dark_gray>You can restore later, but votes won't come back.</dark_gray>"
                )
            )
        )

        // Cancel (left) / Confirm (right) — consistent confirmation layout
        val cancel = icon(Material.RED_DYE, "<red>Cancel</red>", listOf("<gray>Go back without removing.</gray>"))
        inv.setItem(11, cancel)
        setDeny(11, cancel) { admin, _ -> plugin.gui.open(admin, AdminCandidatesMenu(plugin)) }

        val confirm = icon(
            Material.LIME_DYE,
            "<green>Confirm</green>",
            listOf("<gray>Remove this candidate.</gray>")
        )
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { admin, _ ->
            val current = plugin.store.candidateEntry(term, candidate)
            if (current == null) {
                denyMsg(admin, "admin.candidate.not_found", mapOf("name" to displayName))
                plugin.gui.open(admin, AdminCandidatesMenu(plugin))
	            return@setConfirm
            }
            if (current.status != CandidateStatus.PROCESS) {
                denyMsg(admin, "admin.candidates.must_process")
                plugin.gui.open(admin, AdminCandidatesMenu(plugin))
	            return@setConfirm
            }

            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(
                    admin,
                    plugin.adminActions.setCandidateStatus(admin, term, candidate, CandidateStatus.REMOVED, name),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(admin, AdminCandidatesMenu(plugin))
            }
        }
    }
}

