package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import java.time.Instant

/**
 * Read-only perk list for a specific section.
 */
class CandidatePerkSectionMenu(
    plugin: MayorPlugin,
    private val sectionId: String
) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#56ab2f:#a8e063>✨ Perks</gradient> <gray>• $sectionId</gray>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second

        val allowed = plugin.settings.perksAllowed(term)
        val chosen = plugin.store.chosenPerks(term, player.uniqueId)

        // Header
        inv.setItem(
            4,
            icon(
                Material.BOOK,
                "<gold>$sectionId</gold>",
                listOf(
                    "<gray>Selected:</gray> <white>${chosen.size}/$allowed</white>",
                    "<dark_gray>(read-only)</dark_gray>"
                )
            )
        )

        if (!plugin.perks.isPerkSectionAvailable(sectionId)) {
            val reason = plugin.perks.perkSectionBlockReason(sectionId) ?: "Section unavailable."
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    "<red>Section unavailable</red>",
                    listOf("<gray>$reason</gray>")
                )
            )
        } else {
            val perks = plugin.perks.perksForSection(sectionId, includeDisabled = false)
            if (perks.isEmpty()) {
                val reason = plugin.perks.sectionEmptyReason(sectionId)
                    ?: "No enabled perks in this section."
                inv.setItem(
                    22,
                    icon(
                        Material.BARRIER,
                        "<red>No perks</red>",
                        listOf("<gray>$reason</gray>")
                    )
                )
            } else {
                var slot = 10
                for (perk in perks) {
                    if (slot >= inv.size - 10) break
                    val selected = chosen.contains(perk.id)
                    val name = plugin.perks.resolveText(player, perk.displayNameMm)
                    val lore = plugin.perks.resolveLore(player, perk.loreMm)

                    val item = perkIcon(
                        perk.icon,
                        perk.id,
                        (if (selected) "<green>✓</green> " else "<gray>•</gray> ") + name,
                        lore + listOf("", "<dark_gray>This perk is ${if (selected) "selected" else "not selected"}.</dark_gray>")
                    )
                    if (selected) glow(item)
                    inv.setItem(slot, item)

                    slot++
                    if (slot % 9 == 8) slot += 2 // skip right border + next row left border
                }
            }
        }

        // Back
        inv.setItem(45, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(45, inv.getItem(45)!!) { p -> plugin.gui.open(p, CandidatePerkCatalogMenu(plugin)) }
    }

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
            key.startsWith("water_breathing") -> PotionType.WATER_BREATHING
            key.startsWith("slow_falling") -> PotionType.SLOW_FALLING
            key.startsWith("luck") -> PotionType.LUCK
            key.startsWith("strength") -> PotionType.STRENGTH
            key.startsWith("regeneration") -> PotionType.REGENERATION
            else -> null
        }
    }
}






