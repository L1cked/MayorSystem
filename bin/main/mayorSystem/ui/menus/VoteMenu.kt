package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.time.Duration
import java.time.Instant
import java.util.UUID

class VoteMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#a1ffce:#faffd1>🗳 Vote</gradient>")
    override val rows: Int = 6

    private enum class SortMode {
        ONLINE_FIRST,
        ALPHABETICAL,
        MOST_PERKS
    }

    private data class CandidateView(
        val uuid: UUID,
        val name: String,
        val status: CandidateStatus,
        val online: Boolean,
        val perkCount: Int,
        val item: ItemStack
    )

    private var sortMode: SortMode = SortMode.ONLINE_FIRST
    private var filter: String = ""

    // Cache: build candidate heads once per menu instance (per term).
    // Re-ordering / filtering reuses these ItemStacks.
    private var cacheTerm: Int = Int.MIN_VALUE
    private var cache: List<CandidateView> = emptyList()

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.compute(now).second
        val open = plugin.termService.isElectionOpen(now, term)
        val times = plugin.termService.timesFor(term)

        val hasVoted = plugin.store.hasVoted(term, player.uniqueId)
        val votedFor = plugin.store.votedFor(term, player.uniqueId)

        ensureCache(term)

        // -----------------------------------------------------------------
        // Header (top center)
        // -----------------------------------------------------------------
        val timeLeft = Duration.between(now, times.electionClose).coerceAtLeast(Duration.ZERO)
        val headerLore = buildList {
            add("<gray>Term:</gray> <white>#${term + 1}</white>")
            add("<gray>Voting closes:</gray> <white>${timeFmt(times.electionClose)}</white>")
            add("<gray>Time left:</gray> <white>${fmtDuration(timeLeft)}</white>")
            add("")
            add("<gray>Status:</gray> ${if (open) "<green>OPEN</green>" else "<red>CLOSED</red>"}")
            if (hasVoted) {
                val votedName = votedFor?.let { uuid -> cache.firstOrNull { it.uuid == uuid }?.name }
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
                Bukkit.getScheduler().runTask(plugin, Runnable { plugin.gui.open(who, this) })
            }
        }

        if (filter.isNotBlank()) {
            val clear = icon(Material.BARRIER, "<red>Clear search</red>", listOf("<gray>Remove the name filter.</gray>"))
            inv.setItem(52, clear)
            set(52, clear) { p, _ ->
                filter = ""
                plugin.gui.open(p, this)
            }
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(49, back)
        set(49, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }

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

        val filtered = cache
            .asSequence()
            .filter { c -> filter.isBlank() || c.name.contains(filter, ignoreCase = true) }
            .toList()

        val ordered = when (sortMode) {
            SortMode.ONLINE_FIRST -> filtered.sortedWith(compareByDescending<CandidateView> { it.online }.thenBy { it.name.lowercase() })
            SortMode.ALPHABETICAL -> filtered.sortedBy { it.name.lowercase() }
            SortMode.MOST_PERKS -> filtered.sortedWith(compareByDescending<CandidateView> { it.perkCount }.thenBy { it.name.lowercase() })
        }

        val slots = candidateSlots()
        val shown = ordered.take(slots.size)
        for ((index, c) in shown.withIndex()) {
            val slot = slots[index]
            inv.setItem(slot, c.item)

            set(slot, c.item) { p, _ ->
                val canVoteFor = canVoteThisTerm && c.status == CandidateStatus.ACTIVE
                if (canVoteFor) {
                    plugin.gui.open(p, VoteConfirmMenu(plugin, term, c.uuid))
                    return@set
                }

                // Denied action feedback (no silent clicks).
                when {
                    !open -> deny(p, "Voting is closed.")
                    hasVoted -> deny(p, "You already voted this term.")
                    else -> deny(p, "You can't vote for this candidate right now.")
                }
}
        }

        // Hint if they have too many candidates for one page.
        if (ordered.size > slots.size) {
            val more = ordered.size - slots.size
            inv.setItem(44, icon(Material.PAPER, "<gray>More candidates…</gray>", listOf("<dark_gray>+${more} not shown</dark_gray>", "<gray>Use Search to narrow the list.</gray>")))
        }
    }

    private fun ensureCache(term: Int) {
        if (cacheTerm == term && cache.isNotEmpty()) return
        cacheTerm = term

        val candidates = plugin.store.candidates(term, includeRemoved = false)

        cache = candidates.map { c ->
            val online = Bukkit.getPlayer(c.uuid) != null
            val chosen = plugin.store.chosenPerks(term, c.uuid).toList()
            val perkCount = chosen.size
            val bioRaw = plugin.store.candidateBio(term, c.uuid).trim()

            val statusText = when (c.status) {
                CandidateStatus.ACTIVE -> "<green>Active</green>"
                CandidateStatus.PROCESS -> "<gold>In process</gold>"
                CandidateStatus.REMOVED -> "<red>Removed</red>"
            }

            val onlineText = if (online) "<green>Online</green>" else "<dark_gray>Offline</dark_gray>"

            val lore = buildList {
                add("<gray>Status:</gray> $statusText <dark_gray>•</dark_gray> $onlineText")
                add("<gray>Perks:</gray> <white>$perkCount</white>")

                if (chosen.isNotEmpty()) {
                    val preview = chosen.take(2)
                    add("")
                    for (perkId in preview) {
                        add("<dark_gray>•</dark_gray> ${plugin.perks.displayNameFor(term, perkId)}")
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

            val nameColor = if (c.status == CandidateStatus.ACTIVE) "<aqua><bold>${c.lastKnownName}</bold></aqua>" else "<yellow>${c.lastKnownName}</yellow>"
            var item = playerHead(c.uuid, c.lastKnownName, nameColor, lore)
            if (c.status == CandidateStatus.ACTIVE) item = glow(item)

            CandidateView(
                uuid = c.uuid,
                name = c.lastKnownName,
                status = c.status,
                online = online,
                perkCount = perkCount,
                item = item
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
