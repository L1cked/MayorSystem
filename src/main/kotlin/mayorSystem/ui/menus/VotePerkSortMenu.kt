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

    override val title: Component = mm.deserialize("<aqua>Perk Sort</aqua>")
    override val rows: Int = 6

    private var sectionIndex: Int = 0

    override fun titleFor(player: Player): Component {
        val sections = plugin.perks.presetPerks()
            .values
            .groupBy { it.sectionId }
        val keys = plugin.perks.orderedSectionIds(sections.keys)
        if (keys.isEmpty()) return title
        val current = keys.getOrElse(sectionIndex) { keys.first() }
        return mm.deserialize("<aqua>Perk Sort</aqua> <gray>($current)</gray>")
    }

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val term = plugin.termService.computeCached(java.time.Instant.now()).second
        val selected = parent.perkSortSelection().toMutableSet()
        val strict = parent.isPerkSortStrict()

        val sections = plugin.perks.presetPerks()
            .values
            .groupBy { it.sectionId }
        val sectionKeys = plugin.perks.orderedSectionIds(sections.keys)
        if (sectionIndex >= sectionKeys.size) sectionIndex = 0
        val currentSection = sectionKeys.getOrElse(sectionIndex) { "perks" }
        val sectionPerks = sections[currentSection].orEmpty()

        data class Entry(
            val kind: String,
            val sectionId: String,
            val count: Int,
            val perk: mayorSystem.perks.PerkDef?
        )

        val entries = buildList<Entry> {
            sectionPerks.sortedBy { it.displayNameMm.lowercase() }.forEach {
                add(Entry(kind = "perk", sectionId = currentSection, count = 0, perk = it))
            }
        }

        val slots = perkSlots()
        val pageSize = slots.size
        val totalPages = maxOf(1, (entries.size + pageSize - 1) / pageSize)
        val page = 0
        val start = page * pageSize
        val shown = entries.drop(start).take(pageSize)

        val headerLore = buildList {
            add("<gray>Selected:</gray> <white>${selected.size}</white>")
            add("<gray>Mode:</gray> <white>${if (strict) "Strict" else "Non-strict"}</white>")
            add("")
            add("<dark_gray>Non-strict:</dark_gray> <gray>shows candidates with any selected perks.</gray>")
            add("<dark_gray>Strict:</dark_gray> <gray>candidate perks must be within your selection.</gray>")
        }
        inv.setItem(4, icon(Material.BOOK, "<aqua>Perk sort</aqua>", headerLore))

        val strictItem = icon(
            if (strict) Material.LIME_DYE else Material.RED_DYE,
            "<yellow>Strict mode</yellow>",
            listOf(
                "<gray>Status:</gray> <white>${if (strict) "ON" else "OFF"}</white>",
                "<dark_gray>Click to toggle.</dark_gray>"
            )
        )
        inv.setItem(48, strictItem)
        set(48, strictItem) { p, _ ->
            parent.updatePerkSort(selected, !strict)
            plugin.gui.open(p, this)
        }

        val clearItem = icon(
            Material.BARRIER,
            "<red>Clear selection</red>",
            listOf("<gray>Remove all selected perks.</gray>")
        )
        inv.setItem(50, clearItem)
        set(50, clearItem) { p, _ ->
            selected.clear()
            parent.updatePerkSort(selected, strict)
            plugin.gui.open(p, this)
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>", listOf("<dark_gray>Return to vote menu.</dark_gray>"))
        inv.setItem(45, back)
        set(45, back) { p, _ -> plugin.gui.open(p, parent) }

        if (sectionKeys.size > 1) {
            val prevSection = sectionKeys[(sectionIndex - 1 + sectionKeys.size) % sectionKeys.size]
            val nextSection = sectionKeys[(sectionIndex + 1) % sectionKeys.size]
            val prev = icon(Material.ARROW, "<gray> Prev section</gray>", listOf("<gray>Section:</gray> <white>$prevSection</white>"))
            inv.setItem(46, prev)
            set(46, prev) { p, _ ->
                if (sectionIndex <= 0) {
                    sectionIndex = sectionKeys.size - 1
                } else {
                    sectionIndex -= 1
                }
                plugin.gui.open(p, this)
            }

            val next = icon(Material.ARROW, "<gray>Next section </gray>", listOf("<gray>Section:</gray> <white>$nextSection</white>"))
            inv.setItem(53, next)
            set(53, next) { p, _ ->
                sectionIndex = (sectionIndex + 1) % sectionKeys.size
                plugin.gui.open(p, this)
            }
        }

        for ((i, entry) in shown.withIndex()) {
            val slot = slots[i]
            val perk = entry.perk ?: continue
            val isSelected = selected.contains(perk.id)
            val lore = buildList {
                add("<gray>Section:</gray> <white>${perk.sectionId}</white>")
                add("")
                add(if (isSelected) "<green>Selected</green>" else "<gray>Click to select</gray>")
            }
            var item = icon(perk.icon, perk.displayNameMm, lore)
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
