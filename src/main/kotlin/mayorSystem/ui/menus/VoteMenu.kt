package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
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

    override val title: Component = mm.deserialize("<gradient:#a1ffce:#faffd1>🗳 Vote</gradient>")
    override val rows: Int = 6

    override fun titleFor(player: Player): Component {
        val term = plugin.termService.computeCached(Instant.now()).second
        val totalPages = totalPagesFor(term)
        if (page >= totalPages) page = 0
        return mm.deserialize("<gradient:#a1ffce:#faffd1>Vote</gradient> <gray>(Page ${page + 1}/$totalPages)</gray>")
    }

    private enum class SortMode {
        ONLINE_FIRST,
        ALPHABETICAL,
        MOST_PERKS
    }

    private data class CandidateView(
        val uuid: UUID,
        val name: String,
        val status: CandidateStatus,
        val online: Boolean
    )

    private var sortMode: SortMode = SortMode.ONLINE_FIRST
    private var filter: String = ""
    private var page: Int = 0

    // Cache: candidate list per term (items are built lazily per page).
    private var cacheTerm: Int = Int.MIN_VALUE
    private var cache: List<CandidateView> = emptyList()

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second
        val blocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
        if (blocked != null) {
            inv.setItem(22, icon(Material.BARRIER, "<red>Voting unavailable</red>", listOf(blocked)))
            val back = icon(Material.ARROW, "<gray>â¬… Back</gray>")
            inv.setItem(45, back)
            set(45, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }
        val open = plugin.termService.isElectionOpen(now, term)
        val times = plugin.termService.timesFor(term)

        val hasVoted = plugin.store.hasVoted(term, player.uniqueId)
        val votedFor = plugin.store.votedFor(term, player.uniqueId)

        ensureCache(term)
        val nameById = cache.associate { it.uuid to it.name }

        val filtered = cache
            .asSequence()
            .filter { c -> filter.isBlank() || c.name.contains(filter, ignoreCase = true) }
            .toList()
        val perkCounts = if (sortMode == SortMode.MOST_PERKS) {
            filtered.associate { it.uuid to plugin.store.chosenPerks(term, it.uuid).size }
        } else {
            emptyMap()
        }
        val ordered = when (sortMode) {
            SortMode.ONLINE_FIRST -> filtered.sortedWith(compareByDescending<CandidateView> { it.online }.thenBy { it.name.lowercase() })
            SortMode.ALPHABETICAL -> filtered.sortedBy { it.name.lowercase() }
            SortMode.MOST_PERKS -> filtered.sortedWith(compareByDescending<CandidateView> { perkCounts[it.uuid] ?: 0 }.thenBy { it.name.lowercase() })
        }

        val slots = candidateSlots()
        val pageSize = slots.size
        val totalPages = maxOf(1, (ordered.size + pageSize - 1) / pageSize)
        if (page >= totalPages) page = 0
        val start = page * pageSize
        val shown = ordered.drop(start).take(pageSize)

        // -----------------------------------------------------------------
        // Header (top center)
        // -----------------------------------------------------------------
        val timeLeft = Duration.between(now, times.electionClose).coerceAtLeast(Duration.ZERO)
        val headerLore = buildList {
            add("<gray>Term:</gray> <white>#${term + 1}</white>")
            add("<gray>Voting closes:</gray> <white>${timeFmt(times.electionClose)}</white>")
            add("<gray>Time left:</gray> <white>${fmtDuration(timeLeft)}</white>")
            add("<gray>Page:</gray> <white>${page + 1}/$totalPages</white>")
            add("")
            add("<gray>Status:</gray> ${if (open) "<green>OPEN</green>" else "<red>CLOSED</red>"}")
            if (hasVoted) {
                val votedName = votedFor?.let { uuid -> nameById[uuid] }
                    ?: votedFor?.toString()
                    ?: "Unknown"
                add("<gray>You voted for:</gray> <gold>$votedName</gold>")
            }
            add("")
            add("<gray>Sort:</gray> <white>${sortLabel(sortMode)}</white>")
            if (filter.isNotBlank()) add("<gray>Search:</gray> <white>${escapeMm(filter)}</white>")
        }

        inv.setItem(4, icon(Material.CLOCK, "<gold>Vote</gold>", headerLore))

        // -----------------------------------------------------------------
        // Controls (bottom row)
        // -----------------------------------------------------------------
        val sortItem = icon(
            Material.COMPARATOR,
            "<aqua>Sort</aqua>",
            listOf(
                "<gray>Current:</gray> <white>${sortLabel(sortMode)}</white>",
                "",
                "<dark_gray>Click to cycle:</dark_gray>",
                "<gray>•</gray> Online first",
                "<gray>•</gray> Alphabetical",
                "<gray>•</gray> Most perks first"
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
            "<aqua>Search</aqua>",
            listOf(
                "<gray>Filter candidates by name.</gray>",
                if (filter.isBlank()) "<dark_gray>(no active filter)</dark_gray>" else "<gray>Current:</gray> <white>${escapeMm(filter)}</white>",
                "",
                "<dark_gray>Click to type.</dark_gray>"
            )
        )
        inv.setItem(51, searchItem)
        set(51, searchItem) { p, _ ->
            plugin.gui.openAnvilPrompt(
                p,
                mm.deserialize("<gold>Search candidates</gold>"),
                filter
            ) { who, text ->
                // If they cancel, keep the current filter and restore the menu.
                if (text != null) filter = text
                // Empty input clears.
                if (filter.isBlank()) filter = ""
                page = 0
                Bukkit.getScheduler().runTask(plugin, Runnable { plugin.gui.open(who, this) })
            }
        }

        if (filter.isNotBlank()) {
            val clear = icon(Material.BARRIER, "<red>Clear search</red>", listOf("<gray>Remove the name filter.</gray>"))
            inv.setItem(52, clear)
            set(52, clear) { p, _ ->
                filter = ""
                page = 0
                plugin.gui.open(p, this)
            }
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }

        if (totalPages > 1) {
            val prev = icon(
                Material.ARROW,
                "<gray> Prev</gray>",
                listOf("<gray>Page:</gray> <white>${page + 1}/$totalPages</white>")
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
                "<gray>Next </gray>",
                listOf("<gray>Page:</gray> <white>${page + 1}/$totalPages</white>")
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

        // -----------------------------------------------------------------
        // Status chip (bottom row) — doesn't steal a candidate slot.
        // -----------------------------------------------------------------
        val statusSlot = 50
        if (hasVoted) {
            val votedEntry = votedFor?.let { uuid -> plugin.store.candidates(term, includeRemoved = true).firstOrNull { it.uuid == uuid } }
            val votedName = votedEntry?.lastKnownName ?: votedFor?.toString() ?: "Unknown"

            val voteLine = if (plugin.settings.allowVoteChange) {
                "<gray>You can change your vote until the election closes.</gray>"
            } else {
                "<gray>Your vote is locked for this term.</gray>"
            }

            val receipt = if (votedFor != null && votedEntry != null) {
                playerHead(
                    votedFor,
                    votedEntry.lastKnownName,
                    "<gold>Voted: $votedName</gold>",
                    listOf(voteLine, "", "<dark_gray>Click to view perks.</dark_gray>")
                )
            } else {
                icon(Material.PAPER, "<gold>Voted: $votedName</gold>", listOf(voteLine))
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
                            candidateName = votedEntry?.lastKnownName,
                            backToConfirm = null,
                            backToList = { this }
                        )
                    )
                }
            }
        } else if (!open) {
            inv.setItem(statusSlot, icon(Material.BARRIER, "<red>Voting is closed</red>"))
        }

        // -----------------------------------------------------------------
        // Candidate list
        // -----------------------------------------------------------------
        val canVoteThisTerm = open && (!hasVoted || plugin.settings.allowVoteChange)

        for ((index, c) in shown.withIndex()) {
            val slot = slots[index]
            val chosen = plugin.store.chosenPerks(term, c.uuid).toList()
            val perkCount = chosen.size
            val bioRaw = plugin.store.candidateBio(term, c.uuid).trim()

            val statusText = when (c.status) {
                CandidateStatus.ACTIVE -> "<green>Active</green>"
                CandidateStatus.PROCESS -> "<gold>In process</gold>"
                CandidateStatus.REMOVED -> "<red>Removed</red>"
            }

            val onlineText = if (c.online) "<green>Online</green>" else "<dark_gray>Offline</dark_gray>"

            val lore = buildList {
                add("<gray>Status:</gray> $statusText <dark_gray></dark_gray> $onlineText")
                add("<gray>Perks:</gray> <white>$perkCount</white>")

                if (chosen.isNotEmpty()) {
                    val preview = chosen.take(2)
                    add("")
                    for (perkId in preview) {
                        add("<dark_gray></dark_gray> ${plugin.perks.displayNameFor(term, perkId)}")
                    }
                    if (chosen.size > preview.size) {
                        add("<dark_gray>+${chosen.size - preview.size} more</dark_gray>")
                    }
                }

                val bioLine = if (bioRaw.isNotBlank()) wrapLore(bioRaw, 38).firstOrNull() else null
                if (bioLine != null) {
                    add("")
                    add("<gray>Bio:</gray> <white>${escapeMm(bioLine)}</white>")
                }

                add("")
                if (c.status == CandidateStatus.ACTIVE) {
                    add("<green>Click to vote</green>")
                } else {
                    add("<gray>Not eligible to vote</gray>")
                }
            }

            val nameColor = if (c.status == CandidateStatus.ACTIVE) "<aqua><bold>${c.name}</bold></aqua>" else "<yellow>${c.name}</yellow>"
            var item = playerHead(c.uuid, c.name, nameColor, lore)
            if (c.status == CandidateStatus.ACTIVE) item = glow(item)

            inv.setItem(slot, item)
            set(slot, item) { p, _ ->
                val canVoteFor = canVoteThisTerm && c.status == CandidateStatus.ACTIVE
                if (canVoteFor) {
                    plugin.gui.open(p, VoteConfirmMenu(plugin, term, c.uuid))
                    return@set
                }

                // Denied action feedback (no silent clicks).
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
        val count = cache.count { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
        val pageSize = candidateSlots().size
        return maxOf(1, (count + pageSize - 1) / pageSize)
    }

    private fun ensureCache(term: Int) {
        if (cacheTerm == term && cache.isNotEmpty()) return
        cacheTerm = term

        val candidates = plugin.store.candidates(term, includeRemoved = false)

        cache = candidates.map { c ->
            val online = Bukkit.getPlayer(c.uuid) != null
            CandidateView(
                uuid = c.uuid,
                name = c.lastKnownName,
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
        SortMode.ALPHABETICAL -> SortMode.MOST_PERKS
        SortMode.MOST_PERKS -> SortMode.ONLINE_FIRST
    }

    private fun sortLabel(mode: SortMode): String = when (mode) {
        SortMode.ONLINE_FIRST -> "Online first"
        SortMode.ALPHABETICAL -> "Alphabetical"
        SortMode.MOST_PERKS -> "Most perks first"
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
}

