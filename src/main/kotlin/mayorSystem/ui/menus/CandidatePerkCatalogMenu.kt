package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

/**
 * Read-only perk catalog for candidates.
 *
 * Selection happens during the Apply Wizard, so this menu exists purely to browse.
 */
class CandidatePerkCatalogMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#56ab2f:#a8e063>✨ Perks</gradient>")
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
                Material.NETHER_STAR,
                "<gold>Perk Catalog</gold>",
                listOf(
                    "<gray>Selected:</gray> <white>${chosen.size}/$allowed</white>",
                    "<dark_gray>(read-only)</dark_gray>",
                    "<gray>Click a section to view perks.</gray>"
                )
            )
        )

        // Sections
        val sec = plugin.config.getConfigurationSection("perks.sections") ?: return
        var slot = 10
        for (sectionId in sec.getKeys(false)) {
            val base = "perks.sections.$sectionId"
            if (!plugin.config.getBoolean("$base.enabled", true)) continue

            val display = plugin.config.getString("$base.display_name") ?: "<white>$sectionId</white>"
            val iconMat = runCatching {
                Material.valueOf((plugin.config.getString("$base.icon") ?: "CHEST").uppercase())
            }.getOrDefault(Material.CHEST)

            val item = icon(
                iconMat,
                display,
                listOf("<gray>Click to view perks.</gray>")
            )
            inv.setItem(slot, item)
            set(slot, item) { p -> plugin.gui.open(p, CandidatePerkSectionMenu(plugin, sectionId)) }

            slot++
            if (slot % 9 == 8) slot += 2 // skip right border + next row left border
            if (slot >= inv.size - 10) break
        }

        // Back
        inv.setItem(45, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(45, inv.getItem(45)!!) { p -> plugin.gui.open(p, CandidateMenu(plugin)) }
    }
}
