package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

class CandidatePerkCatalogMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = gc("menus.candidate_perk_catalog.title")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second

        val allowed = plugin.settings.perksAllowed(term)
        val chosen = plugin.store.chosenPerks(term, player.uniqueId)

        inv.setItem(
            4,
            icon(
                Material.NETHER_STAR,
                g("menus.candidate_perk_catalog.header.name"),
                listOf(
                    g("menus.candidate_perk_catalog.header.lore.selected", mapOf("selected" to chosen.size.toString(), "allowed" to allowed.toString())),
                    g("menus.candidate_perk_catalog.header.lore.read_only"),
                    g("menus.candidate_perk_catalog.header.lore.click")
                )
            )
        )

        val sec = plugin.config.getConfigurationSection("perks.sections")
        if (sec == null || sec.getKeys(false).isEmpty()) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    g("menus.candidate_perk_catalog.empty.name"),
                    listOf(g("menus.candidate_perk_catalog.empty.lore"))
                )
            )
        } else {
            val slots = contentSlots(inv)
            var slotIndex = 0
            val orderedSections = plugin.perks.orderedSectionIds(sec.getKeys(false))
            for (sectionId in orderedSections) {
                if (slotIndex >= slots.size) break
                val base = "perks.sections.$sectionId"
                if (!plugin.config.getBoolean("$base.enabled", true)) continue
                if (!plugin.perks.isPerkSectionAvailable(sectionId)) continue
                val slot = slots[slotIndex++]

                val display = plugin.config.getString("$base.display_name") ?: "<white>$sectionId</white>"
                val iconMat = runCatching {
                    Material.valueOf((plugin.config.getString("$base.icon") ?: "CHEST").uppercase())
                }.getOrDefault(Material.CHEST)

                val item = icon(
                    iconMat,
                    display,
                    listOf(g("menus.candidate_perk_catalog.section.lore"))
                )
                inv.setItem(slot, item)
                set(slot, item) { p -> plugin.gui.open(p, CandidatePerkSectionMenu(plugin, sectionId)) }
            }
        }

        inv.setItem(45, icon(Material.ARROW, g("menus.common.back.name")))
        set(45, inv.getItem(45)!!) { p -> plugin.gui.open(p, CandidateMenu(plugin)) }
    }
}
