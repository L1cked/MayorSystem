package mayorSystem.elections.ui

import kotlinx.coroutines.launch
import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import mayorSystem.ui.UiClickSound
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import java.util.UUID

class AdminFakeVoteAdjustMenu(
    plugin: MayorPlugin,
    private val term: Int,
    private val candidate: UUID,
    private val candidateName: String,
    private val backToList: () -> Menu
) : Menu(plugin) {

    override val title: Component = gc("menus.admin_fake_vote_adjust.title", mapOf("name" to candidateName))
    override val rows: Int = 5

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val entry = plugin.store.candidates(term, includeRemoved = true).firstOrNull { it.uuid == candidate }
        if (entry == null) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    g("menus.admin_fake_vote_adjust.missing.name"),
                    listOf(g("menus.admin_fake_vote_adjust.missing.lore"))
                )
            )
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(36, back)
            set(36, back) { p, _ -> plugin.gui.open(p, backToList.invoke()) }
            return
        }

        val realVotes = plugin.store.realVoteCounts(term)[candidate] ?: 0
        val fakeVotes = plugin.store.fakeVoteAdjustment(term, candidate)
        val totalVotes = plugin.store.voteCounts(term)[candidate] ?: 0
        val displayName = plugin.playerDisplayNames.resolve(candidate, entry.lastKnownName).mini
        val statusText = when (entry.status) {
            CandidateStatus.ACTIVE -> g("menus.admin_fake_vote_adjust.head.status.active")
            CandidateStatus.PROCESS -> g("menus.admin_fake_vote_adjust.head.status.process")
            CandidateStatus.REMOVED -> g("menus.admin_fake_vote_adjust.head.status.removed")
        }

        val head = playerHead(
            candidate,
            entry.lastKnownName,
            g("menus.admin_fake_vote_adjust.head.name", mapOf("name" to displayName)),
            listOf(
                g("menus.admin_fake_vote_adjust.head.lore.term", mapOf("term" to (term + 1).toString())),
                g("menus.admin_fake_vote_adjust.head.lore.status", mapOf("status" to statusText)),
                g("menus.admin_fake_vote_adjust.head.lore.total", mapOf("votes" to totalVotes.toString())),
                g("menus.admin_fake_vote_adjust.head.lore.real", mapOf("votes" to realVotes.toString())),
                g("menus.admin_fake_vote_adjust.head.lore.fake", mapOf("votes" to fakeVotes.toString())),
                "",
                g("menus.admin_fake_vote_adjust.head.lore.click_view")
            )
        )
        inv.setItem(4, head)
        set(4, head) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_ELECTION_FAKE_VOTES)) return@set
            plugin.gui.open(
                p,
                    mayorSystem.ui.menus.CandidatePerksViewMenu(
                        plugin = plugin,
                        term = term,
                        candidate = candidate,
                        candidateName = displayName,
                        backToConfirm = null,
                        backToList = { this }
                    )
            )
        }

        val decrement = icon(
            Material.RED_DYE,
            g("menus.admin_fake_vote_adjust.decrement.name"),
            listOf(
                g("menus.admin_fake_vote_adjust.decrement.lore.click"),
                g("menus.admin_fake_vote_adjust.decrement.lore.shift")
            )
        )
        inv.setItem(20, decrement)
        set(20, decrement) { p, click ->
            if (!requirePerm(p, Perms.ADMIN_ELECTION_FAKE_VOTES)) return@set
            adjustFakeVotes(p, click, -incrementFor(click), realVotes, fakeVotes)
        }

        val totals = icon(
            Material.PAPER,
            g("menus.admin_fake_vote_adjust.totals.name"),
            listOf(
                g("menus.admin_fake_vote_adjust.totals.lore.total", mapOf("votes" to totalVotes.toString())),
                g("menus.admin_fake_vote_adjust.totals.lore.real", mapOf("votes" to realVotes.toString())),
                g("menus.admin_fake_vote_adjust.totals.lore.fake", mapOf("votes" to fakeVotes.toString())),
                "",
                g("menus.admin_fake_vote_adjust.totals.lore.hint")
            )
        )
        inv.setItem(22, totals)

        val increment = icon(
            Material.LIME_DYE,
            g("menus.admin_fake_vote_adjust.increment.name"),
            listOf(
                g("menus.admin_fake_vote_adjust.increment.lore.click"),
                g("menus.admin_fake_vote_adjust.increment.lore.shift")
            )
        )
        inv.setItem(24, increment)
        set(24, increment) { p, click ->
            if (!requirePerm(p, Perms.ADMIN_ELECTION_FAKE_VOTES)) return@set
            adjustFakeVotes(p, click, incrementFor(click), realVotes, fakeVotes)
        }

        val custom = icon(
            Material.ANVIL,
            g("menus.admin_fake_vote_adjust.custom.name"),
            listOf(
                g("menus.admin_fake_vote_adjust.custom.lore.current", mapOf("votes" to fakeVotes.toString())),
                g("menus.admin_fake_vote_adjust.custom.lore.range", mapOf("min" to (-realVotes).toString(), "max" to MAX_FAKE_VOTES.toString())),
                g("menus.admin_fake_vote_adjust.custom.lore.hint")
            )
        )
        inv.setItem(31, custom)
        set(31, custom) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_ELECTION_FAKE_VOTES)) return@set
            plugin.gui.openAnvilPrompt(
                p,
                gc("menus.admin_fake_vote_adjust.custom.prompt_title"),
                fakeVotes.toString()
            ) { who, text ->
                val raw = text?.trim()
                if (raw.isNullOrBlank()) {
                    Bukkit.getScheduler().runTask(plugin, Runnable { plugin.gui.open(who, this) })
                    return@openAnvilPrompt
                }
                val parsed = raw.toIntOrNull()
                if (parsed == null) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        plugin.messages.msg(who, "admin.election.fake_votes_invalid")
                        plugin.gui.open(who, this)
                    })
                    return@openAnvilPrompt
                }

                val clamped = normalizeFakeVotes(parsed, realVotes)
                plugin.scope.launch(plugin.mainDispatcher) {
                    dispatchResult(
                        who,
                        plugin.adminActions.setFakeVoteAdjustment(who, term, candidate, entry.lastKnownName, clamped),
                        denyOnNonSuccess = true
                    )
                    plugin.gui.open(who, this@AdminFakeVoteAdjustMenu)
                }
            }
        }

        val back = icon(Material.ARROW, g("menus.common.back.name"))
        inv.setItem(36, back)
        set(36, back) { p, _ -> plugin.gui.open(p, backToList.invoke()) }
    }

    private fun adjustFakeVotes(
        player: Player,
        click: ClickType,
        delta: Int,
        realVotes: Int,
        currentFakeVotes: Int
    ) {
        val next = normalizeFakeVotes(currentFakeVotes + delta, realVotes)
        if (next == currentFakeVotes) {
            overrideClickSound(UiClickSound.DENY)
            plugin.gui.open(player, this)
            return
        }
        plugin.scope.launch(plugin.mainDispatcher) {
            dispatchResult(
                player,
                plugin.adminActions.setFakeVoteAdjustment(player, term, candidate, candidateName, next),
                denyOnNonSuccess = true
            )
            plugin.gui.open(player, this@AdminFakeVoteAdjustMenu)
        }
    }

    private fun incrementFor(click: ClickType): Int =
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) SHIFT_INCREMENT else 1

    private fun normalizeFakeVotes(amount: Int, realVotes: Int): Int =
        amount.coerceIn(-realVotes, MAX_FAKE_VOTES)

    private companion object {
        const val SHIFT_INCREMENT = 10
        const val MAX_FAKE_VOTES = 1_000_000
    }
}
