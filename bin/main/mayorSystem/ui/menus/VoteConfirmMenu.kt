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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoteConfirmMenu(
    plugin: MayorPlugin,
    private val term: Int,
    private val candidate: UUID
) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gold>Confirm Vote</gold>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val entry = plugin.store.candidates(term, includeRemoved = true)
            .firstOrNull { it.uuid == candidate }

        val name = entry?.lastKnownName ?: "Unknown"
        val status = entry?.status ?: CandidateStatus.REMOVED

        val bioRaw = plugin.store.candidateBio(term, candidate).trim()
        val bioLines = if (bioRaw.isBlank()) {
            listOf("<gray>No candidate bio.</gray>")
        } else {
            wrapLore(bioRaw, 34).take(3).map { "<gray>$it</gray>" }
        }

        val statusLine = when (status) {
            CandidateStatus.ACTIVE -> "<green>ACTIVE</green>"
            CandidateStatus.PROCESS -> "<gold>PROCESS</gold>"
            CandidateStatus.REMOVED -> "<red>REMOVED</red>"
        }

        inv.setItem(
            13,
            playerHead(
                candidate,
                "<yellow>Vote for $name?</yellow>",
                listOf(
                    "<gray>Term:</gray> <white>#${term + 1}</white>",
                    "<gray>Status:</gray> $statusLine",
                    "",
                    "<gray>Bio:</gray>",
                    *bioLines.toTypedArray(),
                    "",
                    if (plugin.settings.allowVoteChange) "<gray>You can change your vote until the election closes.</gray>"
                    else "<gray>Your vote is final for this term.</gray>",
                    "",
                    "<dark_gray>Click this head to view their perks.</dark_gray>"
                )
            )
        )

        // Let voters inspect the candidate's selected perks before committing.
        val head = inv.getItem(13)!!
        set(13, head) { p, _ ->
            plugin.gui.open(
                p,
                CandidatePerksViewMenu(
                    plugin,
                    term = term,
                    candidate = candidate,
                    candidateName = name,
                    backToConfirm = { VoteConfirmMenu(plugin, term, candidate) },
                    backToList = { VoteMenu(plugin) }
                )
            )
        }

        // Cancel (left) / Confirm (right) — consistent confirmation layout
        val cancel = icon(
            Material.RED_DYE,
            "<red>Cancel</red>",
            listOf("<gray>Go back without voting.</gray>")
        )
        inv.setItem(11, cancel)
        setDeny(11, cancel) { p, _ -> plugin.gui.open(p, VoteMenu(plugin)) }

        val confirm = icon(
            Material.LIME_DYE,
            "<green>Confirm</green>",
            listOf("<gray>Cast your vote now.</gray>")
        )
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p, _ -> confirmVote(p) }
    }

    private fun confirmVote(player: Player) {
        plugin.scope.launch(plugin.mainDispatcher) {
            val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
            if (blocked != null) {
                denyMm(player, blocked)
                plugin.gui.open(player, MainMenu(plugin))
                return@launch
            }
            val now = Instant.now()
            val currentElectionTerm = plugin.termService.computeCached(now).second
            if (currentElectionTerm != term) {
                denyMsg(player, "public.vote_term_changed")
                plugin.gui.open(player, VoteMenu(plugin))
                return@launch
            }

            if (!plugin.termService.isElectionOpen(now, term)) {
                denyMsg(player, "public.vote_closed")
                plugin.gui.open(player, VoteMenu(plugin))
                return@launch
            }

            val previousVote = plugin.store.votedFor(term, player.uniqueId)
            if (previousVote != null && !plugin.settings.allowVoteChange) {
                denyMsg(player, "public.vote_already")
                plugin.gui.open(player, VoteMenu(plugin))
                return@launch
            }

            val entry = plugin.store.candidates(term, includeRemoved = false)
                .firstOrNull { it.uuid == candidate }

            if (entry == null || entry.status != CandidateStatus.ACTIVE) {
                denyMsg(player, "public.vote_candidate_ineligible")
                plugin.gui.open(player, VoteMenu(plugin))
                return@launch
            }

            withContext(Dispatchers.IO) {
                plugin.store.vote(term, player.uniqueId, entry.uuid)
            }
            if (previousVote == null) {
                player.sendMessage("Vote cast for ${entry.lastKnownName}.")
            } else if (previousVote == entry.uuid) {
                player.sendMessage("You're already voting for ${entry.lastKnownName}.")
            } else {
                player.sendMessage("Vote updated to ${entry.lastKnownName}.")
            }
            player.closeInventory()
        }
    }
}
