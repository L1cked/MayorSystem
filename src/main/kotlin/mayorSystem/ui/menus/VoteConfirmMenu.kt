package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.security.Perms
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

    override val title: Component = gc("menus.vote_confirm.title")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val entry = plugin.store.candidates(term, includeRemoved = true)
            .firstOrNull { it.uuid == candidate }

        val name = plugin.playerDisplayNames.resolve(candidate, entry?.lastKnownName).mini
        val status = entry?.status ?: CandidateStatus.REMOVED

        val bioRaw = plugin.store.candidateBio(term, candidate).trim()
        val safeBio = mmSafe(bioRaw)
        val bioLines = if (safeBio.isBlank()) {
            listOf(g("menus.vote_confirm.bio.empty"))
        } else {
            wrapLore(safeBio, 34).take(3).map { g("menus.vote_confirm.bio.line", mapOf("line" to it)) }
        }

        val statusLine = when (status) {
            CandidateStatus.ACTIVE -> g("menus.vote_confirm.status.active")
            CandidateStatus.PROCESS -> g("menus.vote_confirm.status.process")
            CandidateStatus.REMOVED -> g("menus.vote_confirm.status.removed")
        }

        inv.setItem(
            13,
            playerHead(
                candidate,
                g("menus.vote_confirm.head.name", mapOf("name" to name)),
                listOf(
                    g("menus.vote_confirm.head.lore.term", mapOf("term" to (term + 1).toString())),
                    g("menus.vote_confirm.head.lore.status", mapOf("status" to statusLine)),
                    "",
                    g("menus.vote_confirm.head.lore.bio_header"),
                    *bioLines.toTypedArray(),
                    "",
                    if (plugin.settings.allowVoteChange) g("menus.vote_confirm.head.lore.vote_change_yes") else g("menus.vote_confirm.head.lore.vote_change_no"),
                    "",
                    g("menus.vote_confirm.head.lore.perks_hint")
                )
            )
        )

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

        val cancel = icon(
            Material.RED_DYE,
            g("menus.vote_confirm.cancel.name"),
            listOf(g("menus.vote_confirm.cancel.lore"))
        )
        inv.setItem(11, cancel)
        setDeny(11, cancel) { p, _ -> plugin.gui.open(p, VoteMenu(plugin)) }

        val confirm = icon(
            Material.LIME_DYE,
            g("menus.vote_confirm.confirm.name"),
            listOf(g("menus.vote_confirm.confirm.lore"))
        )
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p, _ -> confirmVote(p) }
    }

    private fun confirmVote(player: Player) {
        plugin.scope.launch(plugin.mainDispatcher) {
            val completed = plugin.actionCoordinator.trySerialized("vote:${player.uniqueId}:$term") {
                if (!player.hasPermission(Perms.VOTE)) {
                    denyMsg(player, "errors.no_permission")
                    plugin.gui.open(player, MainMenu(plugin))
                    return@trySerialized
                }
                val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
                if (blocked != null) {
                    denyMm(player, blocked)
                    plugin.gui.open(player, MainMenu(plugin))
                    return@trySerialized
                }
                val now = Instant.now()
                val currentElectionTerm = plugin.termService.computeCached(now).second
                if (currentElectionTerm != term) {
                    denyMsg(player, "public.vote_term_changed")
                    plugin.gui.open(player, VoteMenu(plugin))
                    return@trySerialized
                }

                val denial = plugin.voteAccess.voteAccessDenial(term, player.uniqueId, now)
                if (denial != null) {
                    denyMsg(player, denial.messageKey)
                    plugin.gui.open(player, VoteMenu(plugin))
                    return@trySerialized
                }

                val previousVote = plugin.store.votedFor(term, player.uniqueId)
                val entry = plugin.voteAccess.activeCandidate(term, candidate)

                if (entry == null) {
                    denyMsg(player, "public.vote_candidate_ineligible")
                    plugin.gui.open(player, VoteMenu(plugin))
                    return@trySerialized
                }

                if (previousVote != entry.uuid) {
                    withContext(Dispatchers.IO) {
                        plugin.store.vote(term, player.uniqueId, entry.uuid)
                    }
                }
                if (previousVote == null) {
                    val candidateDisplay = plugin.playerDisplayNames.resolve(entry.uuid, entry.lastKnownName).mini
                    plugin.messages.msg(player, "public.vote_cast", mapOf("name" to candidateDisplay))
                    plugin.termService.broadcastVoteActivity(
                        termIndex = term,
                        voterUuid = player.uniqueId,
                        voterName = player.name,
                        candidateUuid = entry.uuid,
                        candidateName = entry.lastKnownName
                    )
                } else if (previousVote == entry.uuid) {
                    plugin.messages.msg(
                        player,
                        "public.vote_already_same",
                        mapOf("name" to plugin.playerDisplayNames.resolve(entry.uuid, entry.lastKnownName).mini)
                    )
                } else {
                    val candidateDisplay = plugin.playerDisplayNames.resolve(entry.uuid, entry.lastKnownName).mini
                    plugin.messages.msg(player, "public.vote_updated", mapOf("name" to candidateDisplay))
                    plugin.termService.broadcastVoteActivity(
                        termIndex = term,
                        voterUuid = player.uniqueId,
                        voterName = player.name,
                        candidateUuid = entry.uuid,
                        candidateName = entry.lastKnownName
                    )
                }
                if (plugin.hasLeaderboardHologram()) {
                    plugin.leaderboardHologram.refreshIfActive()
                }
                player.closeInventory()
            }
            if (completed == null) {
                denyMsg(player, "errors.action_in_progress")
                plugin.gui.open(player, VoteMenu(plugin))
            }
        }
    }
}
