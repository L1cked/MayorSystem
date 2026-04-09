package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant

class CandidatePerkSectionMenu(
    plugin: MayorPlugin,
    private val sectionId: String
) : Menu(plugin) {

    override val title: Component = gc("menus.candidate_perk_section.title", mapOf("section" to sectionId))
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
                Material.BOOK,
                g("menus.candidate_perk_section.header.name", mapOf("section" to sectionId)),
                listOf(
                    g("menus.candidate_perk_section.header.lore.selected", mapOf("selected" to chosen.size.toString(), "allowed" to allowed.toString())),
                    g("menus.candidate_perk_section.header.lore.read_only")
                )
            )
        )

        if (!plugin.perks.isPerkSectionAvailable(sectionId)) {
            val reason = plugin.perks.perkSectionBlockReason(sectionId) ?: g("menus.candidate_perk_section.unavailable.default_reason")
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    g("menus.candidate_perk_section.unavailable.name"),
                    listOf(g("menus.candidate_perk_section.unavailable.lore", mapOf("reason" to reason)))
                )
            )
        } else {
            val perks = plugin.perks.perksForSection(sectionId, includeDisabled = false)
            if (perks.isEmpty()) {
                val reason = plugin.perks.sectionEmptyReason(sectionId)
                    ?: g("menus.candidate_perk_section.empty.default_reason")
                inv.setItem(
                    22,
                    icon(
                        Material.BARRIER,
                        g("menus.candidate_perk_section.empty.name"),
                        listOf(g("menus.candidate_perk_section.empty.lore", mapOf("reason" to reason)))
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
                        (if (selected) g("menus.candidate_perk_section.perk.selected_prefix") else g("menus.candidate_perk_section.perk.unselected_prefix")) + name,
                        lore + listOf(
                            "",
                            if (selected) g("menus.candidate_perk_section.perk.state_selected") else g("menus.candidate_perk_section.perk.state_unselected")
                        )
                    )
                    if (selected) glow(item)
                    inv.setItem(slot, item)

                    slot++
                    if (slot % 9 == 8) slot += 2
                }
            }
        }

        inv.setItem(45, icon(Material.ARROW, g("menus.common.back.name")))
        set(45, inv.getItem(45)!!) { p -> plugin.gui.open(p, CandidatePerkCatalogMenu(plugin)) }
    }

}
