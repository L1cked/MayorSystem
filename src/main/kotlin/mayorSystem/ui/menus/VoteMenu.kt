package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Duration
import java.time.Instant
import java.util.UUID

class VoteMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = gc("menus.vote.title")
    override val rows: Int = 6

    override fun titleFor(player: Player): Component {
        val term = plugin.termService.computeCached(Instant.now()).second
        val totalPages = totalPagesFor(term)
        if (page >= totalPages) page = 0
        return gc("menus.vote.title_page", mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString()))
    }

    private enum class SortMode {
        ONLINE_FIRST,
        ALPHABETICAL,
        PERK_MATCH
    }

    private data class CandidateView(
        val uuid: UUID,
        val displayName: String,
        val plainName: String,
        val lastKnownName: String,
        val status: CandidateStatus,
        val online: Boolean
    )

    private var sortMode: SortMode = SortMode.ONLINE_FIRST
    private var filter: String = ""
    private var page: Int = 0
    private var perkSortStrict: Boolean = false
    private var perkSortPerks: LinkedHashSet<String> = linkedSetOf()

    private var cacheTerm: Int = Int.MIN_VALUE
    private var cache: List<CandidateView> = emptyList()

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second
        val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
        if (blocked != null) {
            inv.setItem(22, icon(Material.BARRIER, g("menus.vote.unavailable.name"), listOf(blocked)))
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(45, back)
            set(45, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }
        val open = plugin.voteAccess.isElectionOpen(now, term)
        val times = plugin.termService.timesFor(term)

        val hasVoted = plugin.store.hasVoted(term, player.uniqueId)
        val votedFor = plugin.store.votedFor(term, player.uniqueId)

        ensureCache(term)
        val nameById = cache.associate { it.uuid to it.displayName }

        val filtered = filterByName(cache, filter) { it.plainName }
        val perkMatchCounts: Map<UUID, Int>
        val ordered = when {
            sortMode == SortMode.PERK_MATCH && perkSortPerks.isNotEmpty() -> {
                val matches = mutableMapOf<UUID, Int>()
                for (c in filtered) {
                    val perks = plugin.store.chosenPerks(term, c.uuid).toSet()
                    val overlap = perks.count { it in perkSortPerks }
                    if (overlap <= 0) continue
                    if (perkSortStrict && !perks.all { it in perkSortPerks }) continue
                    matches[c.uuid] = overlap
                }
                perkMatchCounts = matches
                filtered
                    .filter { matches.containsKey(it.uuid) }
                    .sortedWith(compareByDescending<CandidateView> { matches[it.uuid] ?: 0 }.thenBy { it.plainName.lowercase() })
            }
            else -> {
                perkMatchCounts = emptyMap()
                when (sortMode) {
                    SortMode.ONLINE_FIRST ->
                        filtered.sortedWith(compareByDescending<CandidateView> { it.online }.thenBy { it.plainName.lowercase() })
                    SortMode.ALPHABETICAL ->
                        filtered.sortedBy { it.plainName.lowercase() }
                    SortMode.PERK_MATCH ->
                        filtered.sortedBy { it.plainName.lowercase() }
                }
            }
        }

        val slots = candidateSlots()
        val pageSize = slots.size
        val totalPages = maxOf(1, (ordered.size + pageSize - 1) / pageSize)
        if (page >= totalPages) page = 0
        val start = page * pageSize
        val shown = ordered.drop(start).take(pageSize)

        val timeLeft = Duration.between(now, times.electionClose).coerceAtLeast(Duration.ZERO)
        val headerLore = buildList {
            add(g("menus.vote.header.lore.term", mapOf("term" to (term + 1).toString())))
            add(g("menus.vote.header.lore.closes", mapOf("time" to timeFmt(times.electionClose))))
            add(g("menus.vote.header.lore.time_left", mapOf("time" to fmtDuration(timeLeft))))
            add(g("menus.vote.header.lore.page", mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())))
            add("")
            add(g("menus.vote.header.lore.status", mapOf("status" to if (open) g("menus.vote.state.open") else g("menus.vote.state.closed"))))
            if (hasVoted) {
                val votedName = votedFor?.let { uuid -> nameById[uuid] }
                    ?: votedFor?.toString()
                    ?: g("menus.vote.unknown_name")
                add(g("menus.vote.header.lore.voted_for", mapOf("name" to votedName)))
            }
            add("")
            add(g("menus.vote.header.lore.sort", mapOf("sort" to sortLabel(sortMode))))
            if (sortMode == SortMode.PERK_MATCH && perkSortPerks.isNotEmpty()) {
                add(
                    g(
                        "menus.vote.header.lore.perk_sort_selected",
                        mapOf(
                            "count" to perkSortPerks.size.toString(),
                            "mode" to if (perkSortStrict) g("menus.vote.perk_sort.mode_strict") else g("menus.vote.perk_sort.mode_non_strict")
                        )
                    )
                )
            } else if (sortMode == SortMode.PERK_MATCH) {
                add(g("menus.vote.header.lore.perk_sort_none"))
            }
            if (filter.isNotBlank()) add(g("menus.vote.header.lore.search", mapOf("filter" to escapeMm(filter))))
        }

        inv.setItem(4, icon(Material.CLOCK, g("menus.vote.header.name"), headerLore))

        val sortItem = icon(
            Material.COMPARATOR,
            g("menus.vote.controls.sort.name"),
            listOf(
                g("menus.vote.controls.sort.lore.current", mapOf("sort" to sortLabel(sortMode))),
                "",
                g("menus.vote.controls.sort.lore.cycle"),
                g("menus.vote.controls.sort.lore.online_first"),
                g("menus.vote.controls.sort.lore.alphabetical"),
                g("menus.vote.controls.sort.lore.perk_match")
            )
        )
        inv.setItem(47, sortItem)
        set(47, sortItem) { p, _ ->
            sortMode = nextSort(sortMode)
            page = 0
            plugin.gui.open(p, this)
        }

        val perkSortItem = icon(
            Material.BOOK,
            g("menus.vote.controls.perk_sort.name"),
            buildList {
                add(g("menus.vote.controls.perk_sort.lore.1"))
                if (perkSortPerks.isEmpty()) {
                    add(g("menus.vote.controls.perk_sort.lore.none"))
                } else {
                    add(g("menus.vote.controls.perk_sort.lore.selected", mapOf("count" to perkSortPerks.size.toString())))
                    add(
                        g(
                            "menus.vote.controls.perk_sort.lore.mode",
                            mapOf("mode" to if (perkSortStrict) g("menus.vote.perk_sort.mode_strict") else g("menus.vote.perk_sort.mode_non_strict"))
                        )
                    )
                }
                add("")
                add(g("menus.vote.controls.perk_sort.lore.configure"))
            }
        )
        inv.setItem(49, perkSortItem)
        set(49, perkSortItem) { p, _ -> plugin.gui.open(p, VotePerkSortMenu(plugin, this)) }

        val searchItem = icon(
            Material.ANVIL,
            g("menus.vote.controls.search.name"),
            listOf(
                g("menus.vote.controls.search.lore.filter"),
                if (filter.isBlank()) g("menus.vote.controls.search.lore.none") else g("menus.vote.controls.search.lore.current", mapOf("filter" to escapeMm(filter))),
                "",
                g("menus.vote.controls.search.lore.type")
            )
        )
        inv.setItem(51, searchItem)
        set(51, searchItem) { p, _ ->
            plugin.gui.openAnvilPrompt(
                p,
                gc("menus.vote.controls.search.prompt_title"),
                filter
            ) { who, text ->
                if (text != null) filter = text.trim()
                if (filter.isBlank()) filter = ""
                page = 0
                Bukkit.getScheduler().runTask(plugin, Runnable { plugin.gui.open(who, this) })
            }
        }

        if (filter.isNotBlank()) {
            val clear = icon(Material.BARRIER, g("menus.vote.controls.clear_search.name"), listOf(g("menus.vote.controls.clear_search.lore")))
            inv.setItem(52, clear)
            set(52, clear) { p, _ ->
                filter = ""
                page = 0
                plugin.gui.open(p, this)
            }
        }

        val back = icon(Material.ARROW, g("menus.common.back.name"))
        inv.setItem(45, back)
        set(45, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }

        if (totalPages > 1) {
            val prev = icon(
                Material.ARROW,
                g("menus.vote.controls.prev.name"),
                listOf(g("menus.vote.controls.prev.lore.page", mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())))
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
                g("menus.vote.controls.next.name"),
                listOf(g("menus.vote.controls.next.lore.page", mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())))
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

        val statusSlot = 50
        if (hasVoted) {
            val votedEntry = votedFor?.let { uuid -> plugin.store.candidates(term, includeRemoved = true).firstOrNull { it.uuid == uuid } }
            val votedName = votedEntry?.let { plugin.playerDisplayNames.resolve(it.uuid, it.lastKnownName).mini }
                ?: votedFor?.toString()
                ?: g("menus.vote.unknown_name")

            val voteLine = if (plugin.settings.allowVoteChange) {
                g("menus.vote.receipt.change_allowed")
            } else {
                g("menus.vote.receipt.locked")
            }

            val receipt = if (votedFor != null && votedEntry != null) {
                playerHead(
                    votedFor,
                    votedEntry.lastKnownName,
                    g("menus.vote.receipt.name", mapOf("name" to votedName)),
                    listOf(voteLine, "", g("menus.vote.receipt.view_perks"))
                )
            } else {
                icon(Material.PAPER, g("menus.vote.receipt.name", mapOf("name" to votedName)), listOf(voteLine))
            }

            inv.setItem(statusSlot, receipt)
            if (votedFor != null) {
                set(statusSlot, receipt) { p, _ ->
                    plugin.gui.open(
                        p,
                        CandidatePerksViewMenu(
                            plugin,
                            term = term,
                            candidate = votedFor,
                            candidateName = votedEntry?.let { plugin.playerDisplayNames.resolve(it.uuid, it.lastKnownName).mini },
                            backToConfirm = null,
                            backToList = { this }
                        )
                    )
                }
            }
        } else if (!open) {
            inv.setItem(statusSlot, icon(Material.BARRIER, g("menus.vote.receipt.closed")))
        }

        val canVoteThisTerm = plugin.voteAccess.voteAccessDenial(term, player.uniqueId, now) == null

        for ((index, c) in shown.withIndex()) {
            val slot = slots[index]
            val chosen = plugin.store.chosenPerks(term, c.uuid).toList()
            val perkCount = chosen.size
            val bioRaw = plugin.store.candidateBio(term, c.uuid).trim()

            val statusText = when (c.status) {
                CandidateStatus.ACTIVE -> g("menus.vote.candidate.status.active")
                CandidateStatus.PROCESS -> g("menus.vote.candidate.status.process")
                CandidateStatus.REMOVED -> g("menus.vote.candidate.status.removed")
            }

            val onlineText = if (c.online) g("menus.vote.candidate.online.online") else g("menus.vote.candidate.online.offline")

            val lore = buildList {
                add(g("menus.vote.candidate.lore.status", mapOf("status" to statusText, "online" to onlineText)))
                add(g("menus.vote.candidate.lore.perks", mapOf("count" to perkCount.toString())))
                if (sortMode == SortMode.PERK_MATCH && perkSortPerks.isNotEmpty()) {
                    val match = perkMatchCounts[c.uuid] ?: 0
                    add(g("menus.vote.candidate.lore.match", mapOf("match" to match.toString(), "total" to perkSortPerks.size.toString())))
                }

                if (chosen.isNotEmpty()) {
                    val preview = chosen.take(2)
                    add("")
                    for (perkId in preview) {
                        add(g("menus.vote.candidate.lore.perk_preview", mapOf("perk" to plugin.perks.displayNameFor(term, perkId, player))))
                    }
                    if (chosen.size > preview.size) {
                        add(g("menus.vote.candidate.lore.perk_more", mapOf("count" to (chosen.size - preview.size).toString())))
                    }
                }

                val bioLine = if (bioRaw.isNotBlank()) wrapLore(bioRaw, 38).firstOrNull() else null
                if (bioLine != null) {
                    add("")
                    add(g("menus.vote.candidate.lore.bio", mapOf("bio" to escapeMm(bioLine))))
                }

                add("")
                if (c.status == CandidateStatus.ACTIVE) {
                    add(g("menus.vote.candidate.lore.click_vote"))
                } else {
                    add(g("menus.vote.candidate.lore.not_eligible"))
                }
            }

            val nameColor = if (c.status == CandidateStatus.ACTIVE) {
                g("menus.vote.candidate.name.active", mapOf("name" to c.displayName))
            } else {
                g("menus.vote.candidate.name.inactive", mapOf("name" to c.displayName))
            }
            var item = playerHead(c.uuid, c.lastKnownName, nameColor, lore)
            if (c.status == CandidateStatus.ACTIVE) item = glow(item)

            inv.setItem(slot, item)
            set(slot, item) { p, _ ->
                if (!p.hasPermission(Perms.VOTE)) {
                    denyMsg(p, "errors.no_permission")
                    return@set
                }
                val canVoteFor = canVoteThisTerm && c.status == CandidateStatus.ACTIVE
                if (canVoteFor) {
                    plugin.gui.open(p, VoteConfirmMenu(plugin, term, c.uuid))
                    return@set
                }

                when {
                    !open -> denyMsg(p, "public.vote_closed")
                    hasVoted -> denyMsg(p, "public.vote_already")
                    else -> denyMsg(p, "public.vote_not_allowed")
                }
            }
        }
    }

    private fun totalPagesFor(term: Int): Int {
        ensureCache(term)
        val count = filterByName(cache, filter) { it.plainName }.size
        val pageSize = candidateSlots().size
        return maxOf(1, (count + pageSize - 1) / pageSize)
    }

    private fun ensureCache(term: Int) {
        if (cacheTerm == term && cache.isNotEmpty()) return
        cacheTerm = term

        val candidates = plugin.store.candidates(term, includeRemoved = false)

        cache = candidates.map { c ->
            val online = Bukkit.getPlayer(c.uuid) != null
            val display = plugin.playerDisplayNames.resolve(c.uuid, c.lastKnownName)
            CandidateView(
                uuid = c.uuid,
                displayName = display.mini,
                plainName = display.plain,
                lastKnownName = c.lastKnownName,
                status = c.status,
                online = online
            )
        }
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
        SortMode.ONLINE_FIRST -> SortMode.ALPHABETICAL
        SortMode.ALPHABETICAL -> SortMode.PERK_MATCH
        SortMode.PERK_MATCH -> SortMode.ONLINE_FIRST
    }

    private fun sortLabel(mode: SortMode): String = when (mode) {
        SortMode.ONLINE_FIRST -> g("menus.vote.sort.online_first")
        SortMode.ALPHABETICAL -> g("menus.vote.sort.alphabetical")
        SortMode.PERK_MATCH -> g("menus.vote.sort.perk_match")
    }

    private fun fmtDuration(d: Duration): String {
        if (d.isZero || d.isNegative) return "0s"
        val seconds = d.seconds
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val mins = (seconds % 3_600) / 60
        val secs = seconds % 60

        return buildString {
            if (days > 0) append(days).append('d').append(' ')
            if (hours > 0 || days > 0) append(hours).append('h').append(' ')
            if (mins > 0 || hours > 0 || days > 0) append(mins).append('m').append(' ')
            append(secs).append('s')
        }.trim()
    }

    private fun escapeMm(input: String): String = input.replace("<", "").replace(">", "")

    internal fun perkSortSelection(): Set<String> = perkSortPerks.toSet()

    internal fun isPerkSortStrict(): Boolean = perkSortStrict

    internal fun updatePerkSort(perks: Set<String>, strict: Boolean) {
        perkSortPerks = LinkedHashSet(perks)
        perkSortStrict = strict
        if (perkSortPerks.isNotEmpty()) {
            sortMode = SortMode.PERK_MATCH
        } else if (sortMode == SortMode.PERK_MATCH) {
            sortMode = SortMode.ONLINE_FIRST
        }
        page = 0
    }
}
