package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.util.UUID

class ElectionRankingMenu(
    plugin: MayorPlugin,
    private val term: Int,
    private val backTo: () -> Menu
) : Menu(plugin) {

    override val title: Component = gc("menus.election_ranking.title")
    override val rows: Int = 6

    override fun titleFor(player: Player): Component {
        val totalPages = totalPagesFor()
        if (page >= totalPages) page = 0
        return gc(
            "menus.election_ranking.title_page",
            mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())
        )
    }

    private enum class SortMode {
        VOTES_DESC,
        VOTES_ASC,
        ALPHABETICAL
    }

    private data class RankedCandidate(
        val uuid: UUID,
        val displayName: String,
        val plainName: String,
        val lastKnownName: String,
        val status: CandidateStatus,
        val online: Boolean,
        val votes: Int
    )

    private var sortMode: SortMode = SortMode.VOTES_DESC
    private var filter: String = ""
    private var page: Int = 0

    override fun draw(player: Player, inv: Inventory) {
        border(inv)
        val candidates = rankedCandidates()

        val filtered = filterByName(candidates, filter) { it.plainName }

        val ordered = sortCandidates(filtered)

        val slots = candidateSlots()
        val pageSize = slots.size
        val totalPages = maxOf(1, (ordered.size + pageSize - 1) / pageSize)
        if (page >= totalPages) page = 0

        val start = page * pageSize
        val shown = ordered.drop(start).take(pageSize)

        val headerLore = buildList {
            add(g("menus.election_ranking.header.lore.term", mapOf("term" to (term + 1).toString())))
            add(g("menus.election_ranking.header.lore.candidates", mapOf("count" to candidates.size.toString())))
            add(g("menus.election_ranking.header.lore.page", mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())))
            add(g("menus.election_ranking.header.lore.sort", mapOf("sort" to sortLabel(sortMode))))
            if (filter.isNotBlank()) {
                add(g("menus.election_ranking.header.lore.search", mapOf("filter" to mmSafe(filter))))
            }
        }
        inv.setItem(4, icon(Material.CLOCK, g("menus.election_ranking.header.name"), headerLore))

        val sortItem = icon(
            Material.COMPARATOR,
            g("menus.election_ranking.controls.sort.name"),
            listOf(
                g("menus.election_ranking.controls.sort.lore.current", mapOf("sort" to sortLabel(sortMode))),
                "",
                g("menus.election_ranking.controls.sort.lore.cycle"),
                g("menus.election_ranking.controls.sort.lore.highest_first"),
                g("menus.election_ranking.controls.sort.lore.lowest_first"),
                g("menus.election_ranking.controls.sort.lore.alphabetical")
            )
        )
        inv.setItem(47, sortItem)
        set(47, sortItem) { p, _ ->
            sortMode = nextSort(sortMode)
            page = 0
            plugin.gui.open(p, this)
        }

        val searchItem = icon(
            Material.ANVIL,
            g("menus.election_ranking.controls.search.name"),
            listOf(
                g("menus.election_ranking.controls.search.lore.filter"),
                if (filter.isBlank()) {
                    g("menus.election_ranking.controls.search.lore.none")
                } else {
                    g("menus.election_ranking.controls.search.lore.current", mapOf("filter" to mmSafe(filter)))
                },
                "",
                g("menus.election_ranking.controls.search.lore.type")
            )
        )
        inv.setItem(51, searchItem)
        set(51, searchItem) { p, _ ->
            plugin.gui.openAnvilPrompt(
                p,
                gc("menus.election_ranking.controls.search.prompt_title"),
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
                g("menus.election_ranking.controls.clear_search.name"),
                listOf(g("menus.election_ranking.controls.clear_search.lore"))
            )
            inv.setItem(52, clear)
            set(52, clear) { p, _ ->
                filter = ""
                page = 0
                plugin.gui.open(p, this)
            }
        }

        val back = icon(Material.ARROW, g("menus.common.back.name"))
        inv.setItem(45, back)
        set(45, back) { p, _ -> plugin.gui.open(p, backTo.invoke()) }

        if (totalPages > 1) {
            val prev = icon(
                Material.ARROW,
                g("menus.election_ranking.controls.prev.name"),
                listOf(g("menus.election_ranking.controls.prev.lore.page", mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())))
            )
            inv.setItem(46, prev)
            set(46, prev) { p, _ ->
                if (page <= 0) {
                    denyMsg(p, "public.vote_page_first")
                    return@set
                }
                page -= 1
                plugin.gui.open(p, this)
            }

            val next = icon(
                Material.ARROW,
                g("menus.election_ranking.controls.next.name"),
                listOf(g("menus.election_ranking.controls.next.lore.page", mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())))
            )
            inv.setItem(53, next)
            set(53, next) { p, _ ->
                if (page >= totalPages - 1) {
                    denyMsg(p, "public.vote_page_last")
                    return@set
                }
                page += 1
                plugin.gui.open(p, this)
            }
        }

        if (shown.isEmpty()) {
            val emptyItem = icon(
                Material.BARRIER,
                if (candidates.isEmpty()) g("menus.election_ranking.empty.name") else g("menus.election_ranking.empty.filtered_name"),
                listOf(
                    if (candidates.isEmpty()) g("menus.election_ranking.empty.lore") else g("menus.election_ranking.empty.filtered_lore")
                )
            )
            inv.setItem(22, emptyItem)
            return
        }

        for ((index, candidate) in shown.withIndex()) {
            val slot = slots[index]
            val rank = start + index + 1
            val statusText = when (candidate.status) {
                CandidateStatus.ACTIVE -> g("menus.election_ranking.candidate.status.active")
                CandidateStatus.PROCESS -> g("menus.election_ranking.candidate.status.process")
                CandidateStatus.REMOVED -> g("menus.election_ranking.candidate.status.removed")
            }
            val onlineText = if (candidate.online) {
                g("menus.election_ranking.candidate.online.online")
            } else {
                g("menus.election_ranking.candidate.online.offline")
            }

            val item = playerHead(
                candidate.uuid,
                candidate.lastKnownName,
                g("menus.election_ranking.candidate.name", mapOf("rank" to rank.toString(), "name" to candidate.displayName)),
                listOf(
                    g("menus.election_ranking.candidate.lore.rank", mapOf("rank" to rank.toString())),
                    g("menus.election_ranking.candidate.lore.votes", mapOf("votes" to candidate.votes.toString())),
                    g("menus.election_ranking.candidate.lore.status", mapOf("status" to statusText, "online" to onlineText)),
                    "",
                    g("menus.election_ranking.candidate.lore.click_view")
                )
            )
            inv.setItem(slot, item)
            set(slot, item) { p, _ ->
                plugin.gui.open(
                    p,
                    CandidatePerksViewMenu(
                        plugin = plugin,
                        term = term,
                        candidate = candidate.uuid,
                        candidateName = candidate.displayName,
                        backToConfirm = null,
                        backToList = { this }
                    )
                )
            }
        }
    }

    private fun rankedCandidates(): List<RankedCandidate> {
        val votes = plugin.store.voteCounts(term)
        return plugin.store.candidates(term, includeRemoved = false)
            .map { entry ->
                val display = plugin.playerDisplayNames.resolve(entry.uuid, entry.lastKnownName)
                RankedCandidate(
                    uuid = entry.uuid,
                    displayName = display.mini,
                    plainName = display.plain,
                    lastKnownName = entry.lastKnownName,
                    status = entry.status,
                    online = Bukkit.getPlayer(entry.uuid) != null,
                    votes = votes[entry.uuid] ?: 0
                )
            }
    }

    private fun totalPagesFor(): Int {
        val count = filterByName(rankedCandidates(), filter) { it.plainName }.size
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

    private fun sortCandidates(candidates: List<RankedCandidate>): List<RankedCandidate> = when (sortMode) {
        SortMode.VOTES_DESC -> candidates.sortedWith(
            compareByDescending<RankedCandidate> { it.votes }
                .thenBy { it.plainName.lowercase() }
        )
        SortMode.VOTES_ASC -> candidates.sortedWith(
            compareBy<RankedCandidate> { it.votes }
                .thenBy { it.plainName.lowercase() }
        )
        SortMode.ALPHABETICAL -> candidates.sortedBy { it.plainName.lowercase() }
    }

    private fun sortLabel(mode: SortMode): String = when (mode) {
        SortMode.VOTES_DESC -> g("menus.election_ranking.sort.highest_first")
        SortMode.VOTES_ASC -> g("menus.election_ranking.sort.lowest_first")
        SortMode.ALPHABETICAL -> g("menus.election_ranking.sort.alphabetical")
    }
}
