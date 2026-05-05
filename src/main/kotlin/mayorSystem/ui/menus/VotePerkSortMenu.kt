package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class VotePerkSortMenu(
    plugin: MayorPlugin,
    private val parent: VoteMenu
) : Menu(plugin) {

    override val title: Component = gc("menus.vote_perk_sort.title")
    override val rows: Int = 6

    private var sectionIndex: Int = 0
    private var page: Int = 0

    override fun titleFor(player: Player): Component {
        val sections = plugin.perks.presetPerks()
            .values
            .groupBy { it.sectionId }
        val keys = plugin.perks.orderedSectionIds(sections.keys)
        if (keys.isEmpty()) return title
        val current = keys.getOrElse(sectionIndex) { keys.first() }
        return gc("menus.vote_perk_sort.title_section", mapOf("section" to current))
    }

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val selected = parent.perkSortSelection().toMutableSet()
        val strict = parent.isPerkSortStrict()

        val sections = plugin.perks.presetPerks()
            .values
            .groupBy { it.sectionId }
        val sectionKeys = plugin.perks.orderedSectionIds(sections.keys)
        if (sectionIndex >= sectionKeys.size) sectionIndex = 0
        val currentSection = sectionKeys.getOrElse(sectionIndex) { "perks" }
        val sectionPerks = sections[currentSection].orEmpty()

        val entries = sectionPerks.sortedBy { it.displayNameMm.lowercase() }

        val slots = perkSlots()
        val pageSize = slots.size
        val totalPages = maxOf(1, (entries.size + pageSize - 1) / pageSize)
        page = page.coerceIn(0, totalPages - 1)
        val start = page * pageSize
        val shown = entries.drop(start).take(pageSize)

        val headerLore = buildList {
            add(g("menus.vote_perk_sort.header.lore.selected", mapOf("count" to selected.size.toString())))
            add(g("menus.vote_perk_sort.header.lore.mode", mapOf("mode" to if (strict) g("menus.vote.perk_sort.mode_strict") else g("menus.vote.perk_sort.mode_non_strict"))))
            add("")
            add(g("menus.vote_perk_sort.header.lore.non_strict"))
            add(g("menus.vote_perk_sort.header.lore.strict"))
            add(g("menus.vote_perk_sort.header.lore.page", mapOf("page" to (page + 1).toString(), "total_pages" to totalPages.toString())))
        }
        inv.setItem(4, icon(Material.BOOK, g("menus.vote_perk_sort.header.name"), headerLore))

        val strictItem = icon(
            if (strict) Material.LIME_DYE else Material.RED_DYE,
            g("menus.vote_perk_sort.strict.name"),
            listOf(
                g("menus.vote_perk_sort.strict.lore.status", mapOf("state" to if (strict) g("menus.common.on") else g("menus.common.off"))),
                g("menus.vote_perk_sort.strict.lore.toggle")
            )
        )
        inv.setItem(48, strictItem)
        set(48, strictItem) { p, _ ->
            parent.updatePerkSort(selected, !strict)
            plugin.gui.open(p, this)
        }

        val clearItem = icon(
            Material.BARRIER,
            g("menus.vote_perk_sort.clear.name"),
            listOf(g("menus.vote_perk_sort.clear.lore"))
        )
        inv.setItem(50, clearItem)
        set(50, clearItem) { p, _ ->
            selected.clear()
            parent.updatePerkSort(selected, strict)
            plugin.gui.open(p, this)
        }

        val back = icon(Material.ARROW, g("menus.common.back.name"), listOf(g("menus.vote_perk_sort.back.lore")))
        inv.setItem(45, back)
        set(45, back) { p, _ -> plugin.gui.open(p, parent) }

        if (sectionKeys.size > 1) {
            val prevSection = sectionKeys[(sectionIndex - 1 + sectionKeys.size) % sectionKeys.size]
            val nextSection = sectionKeys[(sectionIndex + 1) % sectionKeys.size]
            val prev = icon(Material.ARROW, g("menus.vote_perk_sort.prev_section.name"), listOf(g("menus.vote_perk_sort.prev_section.lore", mapOf("section" to prevSection))))
            inv.setItem(46, prev)
            set(46, prev) { p, _ ->
                if (sectionIndex <= 0) {
                    sectionIndex = sectionKeys.size - 1
                } else {
                    sectionIndex -= 1
                }
                page = 0
                plugin.gui.open(p, this)
            }

            val next = icon(Material.ARROW, g("menus.vote_perk_sort.next_section.name"), listOf(g("menus.vote_perk_sort.next_section.lore", mapOf("section" to nextSection))))
            inv.setItem(53, next)
            set(53, next) { p, _ ->
                sectionIndex = (sectionIndex + 1) % sectionKeys.size
                page = 0
                plugin.gui.open(p, this)
            }
        }

        if (totalPages > 1) {
            val prevPage = icon(Material.ARROW, "<gray>Prev page</gray>")
            inv.setItem(47, prevPage)
            set(47, prevPage) { p, _ ->
                if (page <= 0) {
                    denyClick()
                } else {
                    page -= 1
                    plugin.gui.open(p, this)
                }
            }

            val nextPage = icon(Material.ARROW, "<gray>Next page</gray>")
            inv.setItem(51, nextPage)
            set(51, nextPage) { p, _ ->
                if (page >= totalPages - 1) {
                    denyClick()
                } else {
                    page += 1
                    plugin.gui.open(p, this)
                }
            }
        }

        for ((i, perk) in shown.withIndex()) {
            val slot = slots[i]
            val isSelected = selected.contains(perk.id)
            val lore = buildList {
                add(g("menus.vote_perk_sort.perk.lore.section", mapOf("section" to perk.sectionId)))
                add("")
                add(if (isSelected) g("menus.vote_perk_sort.perk.lore.selected") else g("menus.vote_perk_sort.perk.lore.select"))
            }
            val name = plugin.perks.resolveText(player, perk.displayNameMm)
            var item = icon(perk.icon, name, lore)
            if (isSelected) item = glow(item)
            inv.setItem(slot, item)
            set(slot, item) { p, _ ->
                if (isSelected) {
                    selected.remove(perk.id)
                } else {
                    selected.add(perk.id)
                }
                parent.updatePerkSort(selected, strict)
                plugin.gui.open(p, this)
            }
        }
    }

    private fun perkSlots(): List<Int> {
        val out = ArrayList<Int>(28)
        for (row in 1..4) {
            for (col in 1..7) {
                out += row * 9 + col
            }
        }
        return out
    }
}
