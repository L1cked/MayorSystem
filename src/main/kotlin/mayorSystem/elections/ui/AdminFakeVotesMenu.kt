package mayorSystem.elections.ui

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.util.UUID

class AdminFakeVotesMenu(
    plugin: MayorPlugin,
    private val term: Int
) : Menu(plugin) {

    override val title: Component = gc("menus.admin_fake_votes.title")
    override val rows: Int = 6

    private enum class SortMode {
        VOTES_DESC,
        VOTES_ASC,
        ALPHABETICAL
    }

    private data class CandidateVotes(
        val uuid: UUID,
        val name: String,
        val status: CandidateStatus,
        val online: Boolean,
        val realVotes: Int,
        val fakeVotes: Int,
        val totalVotes: Int
    )

    private var sortMode: SortMode = SortMode.VOTES_DESC
    private var filter: String = ""
    private var page: Int = 0

    override fun titleFor(player: Player): Component {
        val totalPages = totalPagesFor()
        if (page >= totalPages) page = 0
        return gc(
            "menus.admin_fake_votes.title_page",
            mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())
        )
    }

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val candidates = candidateVotes()
        val filtered = filterByName(candidates, filter) { it.name }
        val ordered = sortCandidates(filtered)

        val slots = candidateSlots()
        val pageSize = slots.size
        val totalPages = maxOf(1, (ordered.size + pageSize - 1) / pageSize)
        if (page >= totalPages) page = 0

        val start = page * pageSize
        val shown = ordered.drop(start).take(pageSize)

        inv.setItem(
            4,
            icon(
                Material.BOOK,
                g("menus.admin_fake_votes.header.name"),
                buildList {
                    add(g("menus.admin_fake_votes.header.lore.term", mapOf("term" to (term + 1).toString())))
                    add(g("menus.admin_fake_votes.header.lore.candidates", mapOf("count" to candidates.size.toString())))
                    add(g("menus.admin_fake_votes.header.lore.page", mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())))
                    add(g("menus.admin_fake_votes.header.lore.sort", mapOf("sort" to sortLabel(sortMode))))
                    if (filter.isNotBlank()) {
                        add(g("menus.admin_fake_votes.header.lore.search", mapOf("filter" to mmSafe(filter))))
                    }
                }
            )
        )

        val sortItem = icon(
            Material.COMPARATOR,
            g("menus.admin_fake_votes.controls.sort.name"),
            listOf(
                g("menus.admin_fake_votes.controls.sort.lore.current", mapOf("sort" to sortLabel(sortMode))),
                "",
                g("menus.admin_fake_votes.controls.sort.lore.cycle"),
                g("menus.admin_fake_votes.controls.sort.lore.highest_first"),
                g("menus.admin_fake_votes.controls.sort.lore.lowest_first"),
                g("menus.admin_fake_votes.controls.sort.lore.alphabetical")
            )
        )
        inv.setItem(47, sortItem)
        set(47, sortItem) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_ELECTION_FAKE_VOTES)) return@set
            sortMode = nextSort(sortMode)
            page = 0
            plugin.gui.open(p, this)
        }

        val searchItem = icon(
            Material.ANVIL,
            g("menus.admin_fake_votes.controls.search.name"),
            listOf(
                g("menus.admin_fake_votes.controls.search.lore.filter"),
                if (filter.isBlank()) {
                    g("menus.admin_fake_votes.controls.search.lore.none")
                } else {
                    g("menus.admin_fake_votes.controls.search.lore.current", mapOf("filter" to mmSafe(filter)))
                },
                "",
                g("menus.admin_fake_votes.controls.search.lore.type")
            )
        )
        inv.setItem(51, searchItem)
        set(51, searchItem) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_ELECTION_FAKE_VOTES)) return@set
            plugin.gui.openAnvilPrompt(
                p,
                gc("menus.admin_fake_votes.controls.search.prompt_title"),
                filter
            ) { who, text ->
                filter = text?.trim().orEmpty()
                page = 0
                Bukkit.getScheduler().runTask(plugin, Runnable { plugin.gui.open(who, this) })
            }
        }

        if (filter.isNotBlank()) {
            val clear = icon(
                Material.BARRIER,
                g("menus.admin_fake_votes.controls.clear_search.name"),
                listOf(g("menus.admin_fake_votes.controls.clear_search.lore"))
            )
            inv.setItem(52, clear)
            set(52, clear) { p, _ ->
                if (!requirePerm(p, Perms.ADMIN_ELECTION_FAKE_VOTES)) return@set
                filter = ""
                page = 0
                plugin.gui.open(p, this)
            }
        }

        val back = icon(Material.ARROW, g("menus.common.back.name"))
        inv.setItem(45, back)
        set(45, back) { p, _ -> plugin.gui.open(p, AdminElectionMenu(plugin)) }

        if (totalPages > 1) {
            val prev = icon(
                Material.ARROW,
                g("menus.admin_fake_votes.controls.prev.name"),
                listOf(g("menus.admin_fake_votes.controls.prev.lore.page", mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())))
            )
            inv.setItem(46, prev)
            set(46, prev) { p, _ ->
                if (!requirePerm(p, Perms.ADMIN_ELECTION_FAKE_VOTES)) return@set
                if (page <= 0) {
                    denyMsg(p, "public.vote_page_first")
                    return@set
                }
                page -= 1
                plugin.gui.open(p, this)
            }

            val next = icon(
                Material.ARROW,
                g("menus.admin_fake_votes.controls.next.name"),
                listOf(g("menus.admin_fake_votes.controls.next.lore.page", mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())))
            )
            inv.setItem(53, next)
            set(53, next) { p, _ ->
                if (!requirePerm(p, Perms.ADMIN_ELECTION_FAKE_VOTES)) return@set
                if (page >= totalPages - 1) {
                    denyMsg(p, "public.vote_page_last")
                    return@set
                }
                page += 1
                plugin.gui.open(p, this)
            }
        }

        if (shown.isEmpty()) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    if (candidates.isEmpty()) g("menus.admin_fake_votes.empty.name") else g("menus.admin_fake_votes.empty.filtered_name"),
                    listOf(
                        if (candidates.isEmpty()) g("menus.admin_fake_votes.empty.lore") else g("menus.admin_fake_votes.empty.filtered_lore")
                    )
                )
            )
            return
        }

        shown.forEachIndexed { index, candidate ->
            val statusText = when (candidate.status) {
                CandidateStatus.ACTIVE -> g("menus.admin_fake_votes.candidate.status.active")
                CandidateStatus.PROCESS -> g("menus.admin_fake_votes.candidate.status.process")
                CandidateStatus.REMOVED -> g("menus.admin_fake_votes.candidate.status.removed")
            }
            val onlineText = if (candidate.online) {
                g("menus.admin_fake_votes.candidate.online.online")
            } else {
                g("menus.admin_fake_votes.candidate.online.offline")
            }
            val item = playerHead(
                candidate.uuid,
                candidate.name,
                g("menus.admin_fake_votes.candidate.name", mapOf("name" to candidate.name)),
                listOf(
                    g("menus.admin_fake_votes.candidate.lore.total", mapOf("votes" to candidate.totalVotes.toString())),
                    g("menus.admin_fake_votes.candidate.lore.real", mapOf("votes" to candidate.realVotes.toString())),
                    g("menus.admin_fake_votes.candidate.lore.fake", mapOf("votes" to candidate.fakeVotes.toString())),
                    g("menus.admin_fake_votes.candidate.lore.status", mapOf("status" to statusText, "online" to onlineText)),
                    "",
                    g("menus.admin_fake_votes.candidate.lore.click_view")
                )
            )
            val slot = slots[index]
            inv.setItem(slot, item)
            set(slot, item) { p, _ ->
                if (!requirePerm(p, Perms.ADMIN_ELECTION_FAKE_VOTES)) return@set
                plugin.gui.open(
                    p,
                    AdminFakeVoteAdjustMenu(
                        plugin = plugin,
                        term = term,
                        candidate = candidate.uuid,
                        candidateName = candidate.name,
                        backToList = { this }
                    )
                )
            }
        }
    }

    private fun candidateVotes(): List<CandidateVotes> {
        val realVotes = plugin.store.realVoteCounts(term)
        val fakeVotes = plugin.store.fakeVoteAdjustments(term)
        val totals = plugin.store.voteCounts(term)

        return plugin.store.candidates(term, includeRemoved = false)
            .map { entry ->
                CandidateVotes(
                    uuid = entry.uuid,
                    name = entry.lastKnownName,
                    status = entry.status,
                    online = Bukkit.getPlayer(entry.uuid) != null,
                    realVotes = realVotes[entry.uuid] ?: 0,
                    fakeVotes = fakeVotes[entry.uuid] ?: 0,
                    totalVotes = totals[entry.uuid] ?: 0
                )
            }
    }

    private fun totalPagesFor(): Int {
        val count = filterByName(candidateVotes(), filter) { it.name }.size
        val pageSize = candidateSlots().size
        return maxOf(1, (count + pageSize - 1) / pageSize)
    }

    private fun candidateSlots(): List<Int> {
        val out = ArrayList<Int>(28)
        for (row in 1..4) {
            for (col in 1..7) {
                out += row * 9 + col
            }
        }
        return out
    }

    private fun nextSort(mode: SortMode): SortMode = when (mode) {
        SortMode.VOTES_DESC -> SortMode.VOTES_ASC
        SortMode.VOTES_ASC -> SortMode.ALPHABETICAL
        SortMode.ALPHABETICAL -> SortMode.VOTES_DESC
    }

    private fun sortLabel(mode: SortMode): String = when (mode) {
        SortMode.VOTES_DESC -> g("menus.admin_fake_votes.sort.highest_first")
        SortMode.VOTES_ASC -> g("menus.admin_fake_votes.sort.lowest_first")
        SortMode.ALPHABETICAL -> g("menus.admin_fake_votes.sort.alphabetical")
    }

    private fun sortCandidates(candidates: List<CandidateVotes>): List<CandidateVotes> = when (sortMode) {
        SortMode.VOTES_DESC -> candidates.sortedWith(
            compareByDescending<CandidateVotes> { it.totalVotes }
                .thenBy { it.name.lowercase() }
        )
        SortMode.VOTES_ASC -> candidates.sortedWith(
            compareBy<CandidateVotes> { it.totalVotes }
                .thenBy { it.name.lowercase() }
        )
        SortMode.ALPHABETICAL -> candidates.sortedBy { it.name.lowercase() }
    }
}
