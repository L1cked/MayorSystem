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
        val now = Instant.now()
        val currentElectionTerm = plugin.termService.computeCached(now).second
        if (currentElectionTerm != term) {
            deny(player, "That election term changed. Please vote again.")
            plugin.gui.open(player, VoteMenu(plugin))
            return
        }

        if (!plugin.termService.isElectionOpen(now, term)) {
            deny(player, "Voting is closed.")
            plugin.gui.open(player, VoteMenu(plugin))
            return
        }

        val previousVote = plugin.store.votedFor(term, player.uniqueId)
        if (previousVote != null && !plugin.settings.allowVoteChange) {
            deny(player, "You already voted this term.")
            plugin.gui.open(player, VoteMenu(plugin))
            return
        }

        val entry = plugin.store.candidates(term, includeRemoved = false)
            .firstOrNull { it.uuid == candidate }

        if (entry == null || entry.status != CandidateStatus.ACTIVE) {
            deny(player, "That candidate is not eligible right now.")
            plugin.gui.open(player, VoteMenu(plugin))
            return
        }

        plugin.store.vote(term, player.uniqueId, entry.uuid)
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
