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
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import java.util.UUID

/**
 * Read-only candidate profile view.
 *
 * Used by voters from the vote confirmation screen.
 *
 * Layout (4 rows):
 * - Border panes (top, bottom, sides)
 * - Row 2 center: candidate head with ALL details + bio
 * - Row 3 center: perk icons (paged, 5 per page) with arrows when needed
 */
class CandidatePerksViewMenu(
    plugin: MayorPlugin,
    private val term: Int,
    private val candidate: UUID,
    private val candidateName: String? = null,
    private val backToConfirm: (() -> Menu)? = null,
    private val backToList: () -> Menu
) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gold>${candidateName ?: "Candidate"}</gold>")
    override val rows: Int = 4

    private var perkPage: Int = 0

    override fun draw(player: Player, inv: Inventory) {
        // Top/bottom/sides panes.
        border(inv)

        val entry = plugin.store.candidates(term, includeRemoved = true).firstOrNull { it.uuid == candidate }
        val name = candidateName ?: (entry?.lastKnownName ?: "Unknown")
        val status = entry?.status ?: CandidateStatus.REMOVED

        val online = Bukkit.getPlayer(candidate) != null
        val votes = plugin.store.voteCounts(term)[candidate] ?: 0

        val statusLine = when (status) {
            CandidateStatus.ACTIVE -> "<green>ACTIVE</green>"
            CandidateStatus.PROCESS -> "<gold>PROCESS</gold>"
            CandidateStatus.REMOVED -> "<red>REMOVED</red>"
        }

        val bioRaw = plugin.store.candidateBio(term, candidate).trim()
        fun escapeMm(input: String): String = input.replace("<", "").replace(">", "")

        val bioLinesMm = if (bioRaw.isBlank()) {
            listOf("<gray>No bio set.</gray>")
        } else {
            // Keep this readable; show a decent chunk in-lore.
            val wrapped = wrapLore(bioRaw, 34)
            val shown = wrapped.take(8).map { "<gray>${escapeMm(it)}</gray>" }
            if (wrapped.size > shown.size) shown + "<dark_gray>+ more…</dark_gray>" else shown
        }

        val chosenIds = plugin.store.chosenPerks(term, candidate).toList()
        val defsById = plugin.perks.availablePerksForCandidate(term, candidate).associateBy { it.id }
        val perks: List<PerkDef> = chosenIds
            .map { id -> defsById[id] ?: unknownDef(id) }
            .sortedBy { it.displayNameMm.lowercase() }

        // -----------------------------------------------------------------
        // Candidate head (row 2 center)
        // -----------------------------------------------------------------
        val headLore = buildList {
            add("<gray>Term:</gray> <white>#${term + 1}</white>")
            add("<gray>Status:</gray> $statusLine")
            add("<gray>Online:</gray> <white>${if (online) "Yes" else "No"}</white>")
            add("<gray>Votes:</gray> <white>$votes</white>")
            add("<gray>Perks:</gray> <white>${perks.size}</white>")
            add("")
            add("<gold>Bio</gold>")
            bioLinesMm.forEach { add(it) }
        }

        val head = playerHead(candidate, entry?.lastKnownName, "<yellow>$name</yellow>", headLore)
        inv.setItem(13, head)

        // -----------------------------------------------------------------
        // Perks (row 3)
        // Row 3 = slots 18..26, with sides (18/26) as panes already.
        // We display up to 5 perks per page in slots 20..24.
        // Paging controls are on row 4 (bottom) so they replace the border panes:
        // - Prev arrow: row 4, column 4  -> slot 30
        // - Next arrow: row 4, column 6  -> slot 32
        // -----------------------------------------------------------------
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
            inv.setItem(slot, perkIcon(perk.icon, perk.id, perk.displayNameMm, perk.loreMm))
        }

        if (totalPages > 1) {
            // Left arrow (prev) - row 4 col 4 (slot 30)
            if (perkPage > 0) {
                val prev = icon(
                    Material.ARROW,
                    "<gray>◀ Prev</gray>",
                    listOf(
                        "<dark_gray>Page ${perkPage} / $totalPages</dark_gray>",
                        "<gray>Click to view previous perks.</gray>"
                    )
                )
                inv.setItem(30, prev)
                set(30, prev) { p, _ ->
                    perkPage -= 1
                    plugin.gui.open(p, this)
                }
            }

            // Right arrow (next) - row 4 col 6 (slot 32)
            if (perkPage < totalPages - 1) {
                val next = icon(
                    Material.ARROW,
                    "<gray>Next ▶</gray>",
                    listOf(
                        "<dark_gray>Page ${perkPage + 2} / $totalPages</dark_gray>",
                        "<gray>Click to view more perks.</gray>"
                    )
                )
                inv.setItem(32, next)
                set(32, next) { p, _ ->
                    perkPage += 1
                    plugin.gui.open(p, this)
                }
            }
        }

        // -----------------------------------------------------------------
        // Back button
        // Move it to the bottom-left (row 4, column 1) and remove the
        // separate "Back to list" arrow.
        // -----------------------------------------------------------------
        val backSlot = 27 // row 4, col 1
        if (backToConfirm != null) {
            val backConfirm = icon(Material.ARROW, "<gray>⬅ Back</gray>", listOf("<dark_gray>Back to confirm</dark_gray>"))
            inv.setItem(backSlot, backConfirm)
            set(backSlot, backConfirm) { p -> plugin.gui.open(p, backToConfirm.invoke()) }
        } else {
            val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
            inv.setItem(backSlot, back)
            set(backSlot, back) { p -> plugin.gui.open(p, backToList.invoke()) }
        }
    }

    private fun perkSlotsForCount(baseSlots: List<Int>, n: Int): List<Int> {
        // baseSlots size is 5.
        return when (n) {
            0 -> emptyList()
            1 -> listOf(baseSlots[2])
            2 -> listOf(baseSlots[1], baseSlots[3])
            3 -> listOf(baseSlots[1], baseSlots[2], baseSlots[3])
            4 -> listOf(baseSlots[0], baseSlots[1], baseSlots[3], baseSlots[4]) // even-ish spacing
            else -> baseSlots
        }
    }

    private fun unknownDef(id: String): PerkDef = PerkDef(
        id = id,
        displayNameMm = "<red>Unknown perk</red>",
        loreMm = listOf(
            "<dark_gray>$id</dark_gray>",
            "<gray>This perk no longer exists in config, or was disabled.</gray>"
        ),
        icon = Material.BARRIER,
        onStart = emptyList(),
        onEnd = emptyList(),
        sectionId = "unknown"
    )

    private fun perkIcon(mat: Material, perkId: String, nameMm: String, loreMm: List<String>): ItemStack {
        val item = icon(mat, nameMm, loreMm)
        if (!isPotionMaterial(mat)) return item
        val potionType = potionTypeFor(perkId) ?: return item
        val meta = item.itemMeta as? PotionMeta ?: return item
        meta.setBasePotionType(potionType)
        item.itemMeta = meta
        return item
    }

    private fun isPotionMaterial(mat: Material): Boolean =
        mat == Material.POTION || mat == Material.SPLASH_POTION || mat == Material.LINGERING_POTION

    private fun potionTypeFor(perkId: String): PotionType? {
        val key = perkId.lowercase()
        return when {
            key.startsWith("speed") -> PotionType.SWIFTNESS
            key.startsWith("jump_boost") || key.startsWith("jump") -> PotionType.LEAPING
            key.startsWith("night_vision") -> PotionType.NIGHT_VISION
            key.startsWith("fire_resistance") -> PotionType.FIRE_RESISTANCE
            key.startsWith("strength") -> PotionType.STRENGTH
            key.startsWith("water_breathing") -> PotionType.WATER_BREATHING
            key.startsWith("slow_falling") -> PotionType.SLOW_FALLING
            key.startsWith("luck") -> PotionType.LUCK
            key.startsWith("regeneration") -> PotionType.REGENERATION
            key.startsWith("resistance") -> PotionType.TURTLE_MASTER
            else -> null
        }
    }
}






