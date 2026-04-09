package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.perks.PerkDef
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.util.UUID

class CandidatePerksViewMenu(
    plugin: MayorPlugin,
    private val term: Int,
    private val candidate: UUID,
    private val candidateName: String? = null,
    private val backToConfirm: (() -> Menu)? = null,
    private val backToList: () -> Menu
) : Menu(plugin) {

    override val title: Component = gc(
        "menus.candidate_perks_view.title",
        mapOf("candidate" to (candidateName ?: g("menus.candidate_perks_view.default_candidate")))
    )
    override val rows: Int = 4

    private var perkPage: Int = 0

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val entry = plugin.store.candidates(term, includeRemoved = true).firstOrNull { it.uuid == candidate }
        val name = candidateName ?: (entry?.lastKnownName ?: g("menus.candidate_perks_view.unknown_name"))
        val status = entry?.status ?: CandidateStatus.REMOVED

        val online = Bukkit.getPlayer(candidate) != null
        val votes = plugin.store.voteCounts(term)[candidate] ?: 0

        val statusLine = when (status) {
            CandidateStatus.ACTIVE -> g("menus.candidate_perks_view.status.active")
            CandidateStatus.PROCESS -> g("menus.candidate_perks_view.status.process")
            CandidateStatus.REMOVED -> g("menus.candidate_perks_view.status.removed")
        }

        val bioRaw = plugin.store.candidateBio(term, candidate).trim()
        fun escapeMm(input: String): String = input.replace("<", "").replace(">", "")

        val bioLinesMm = if (bioRaw.isBlank()) {
            listOf(g("menus.candidate_perks_view.bio.empty"))
        } else {
            val wrapped = wrapLore(bioRaw, 34)
            val shown = wrapped.take(8).map { g("menus.candidate_perks_view.bio.line", mapOf("line" to escapeMm(it))) }
            if (wrapped.size > shown.size) shown + g("menus.candidate_perks_view.bio.more") else shown
        }

        val chosenIds = plugin.store.chosenPerks(term, candidate).toList()
        val defsById = plugin.perks.availablePerksForCandidate(term, candidate).associateBy { it.id }
        val perks: List<PerkDef> = chosenIds
            .map { id -> defsById[id] ?: unknownDef(id) }
            .sortedBy { it.displayNameMm.lowercase() }

        val headLore = buildList {
            add(g("menus.candidate_perks_view.head.lore.term", mapOf("term" to (term + 1).toString())))
            add(g("menus.candidate_perks_view.head.lore.status", mapOf("status" to statusLine)))
            add(g("menus.candidate_perks_view.head.lore.online", mapOf("online" to if (online) g("menus.common.yes") else g("menus.common.no"))))
            add(g("menus.candidate_perks_view.head.lore.votes", mapOf("votes" to votes.toString())))
            add(g("menus.candidate_perks_view.head.lore.perks", mapOf("count" to perks.size.toString())))
            add("")
            add(g("menus.candidate_perks_view.head.lore.bio_header"))
            bioLinesMm.forEach { add(it) }
        }

        val head = playerHead(candidate, entry?.lastKnownName, g("menus.candidate_perks_view.head.name", mapOf("name" to name)), headLore)
        inv.setItem(13, head)

        val pageSize = 5
        val totalPages = ((perks.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        perkPage = perkPage.coerceIn(0, totalPages - 1)

        val from = perkPage * pageSize
        val pagePerks = perks.drop(from).take(pageSize)

        val baseSlots = listOf(20, 21, 22, 23, 24)
        val perkSlots = perkSlotsForCount(baseSlots, pagePerks.size)

        for (i in pagePerks.indices) {
            val perk = pagePerks[i]
            val slot = perkSlots[i]
            val perkName = plugin.perks.resolveText(player, perk.displayNameMm)
            val lore = plugin.perks.resolveLore(player, perk.loreMm)
            inv.setItem(slot, perkIcon(perk.icon, perk.id, perkName, lore))
        }

        if (totalPages > 1) {
            if (perkPage > 0) {
                val prev = icon(
                    Material.ARROW,
                    g("menus.candidate_perks_view.prev.name"),
                    listOf(
                        g("menus.candidate_perks_view.prev.lore.page", mapOf("page" to perkPage.toString(), "total" to totalPages.toString())),
                        g("menus.candidate_perks_view.prev.lore.hint")
                    )
                )
                inv.setItem(30, prev)
                set(30, prev) { p, _ ->
                    perkPage -= 1
                    plugin.gui.open(p, this)
                }
            }

            if (perkPage < totalPages - 1) {
                val next = icon(
                    Material.ARROW,
                    g("menus.candidate_perks_view.next.name"),
                    listOf(
                        g("menus.candidate_perks_view.next.lore.page", mapOf("page" to (perkPage + 2).toString(), "total" to totalPages.toString())),
                        g("menus.candidate_perks_view.next.lore.hint")
                    )
                )
                inv.setItem(32, next)
                set(32, next) { p, _ ->
                    perkPage += 1
                    plugin.gui.open(p, this)
                }
            }
        }

        val backSlot = 27
        if (backToConfirm != null) {
            val backConfirm = icon(Material.ARROW, g("menus.common.back.name"), listOf(g("menus.candidate_perks_view.back_confirm.lore")))
            inv.setItem(backSlot, backConfirm)
            set(backSlot, backConfirm) { p -> plugin.gui.open(p, backToConfirm.invoke()) }
        } else {
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(backSlot, back)
            set(backSlot, back) { p -> plugin.gui.open(p, backToList.invoke()) }
        }
    }

    private fun perkSlotsForCount(baseSlots: List<Int>, n: Int): List<Int> {
        return when (n) {
            0 -> emptyList()
            1 -> listOf(baseSlots[2])
            2 -> listOf(baseSlots[1], baseSlots[3])
            3 -> listOf(baseSlots[1], baseSlots[2], baseSlots[3])
            4 -> listOf(baseSlots[0], baseSlots[1], baseSlots[3], baseSlots[4])
            else -> baseSlots
        }
    }

    private fun unknownDef(id: String): PerkDef = PerkDef(
        id = id,
        displayNameMm = g("menus.candidate_perks_view.unknown_perk.name"),
        loreMm = listOf(
            g("menus.candidate_perks_view.unknown_perk.lore.id", mapOf("id" to id)),
            g("menus.candidate_perks_view.unknown_perk.lore.reason")
        ),
        adminLoreMm = emptyList(),
        icon = Material.BARRIER,
        onStart = emptyList(),
        onEnd = emptyList(),
        sectionId = "unknown",
        origin = mayorSystem.perks.PerkOrigin.INTERNAL,
        enabled = false
    )

}
