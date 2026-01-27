package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import mayorSystem.ui.UiClickSound
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminPerkSectionMenu(plugin: MayorPlugin, private val sectionId: String) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gold>📂 Section:</gold> <white>$sectionId</white>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val hasPerm = player.hasPermission(Perms.ADMIN_PERKS_CATALOG)
                || player.hasPermission(Perms.LEGACY_ADMIN_PERKS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
        if (!hasPerm) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have permission to manage the perk catalog.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
            inv.setItem(49, back)
            set(49, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
            return
        }

        val base = "perks.sections.$sectionId"
        val enabled = plugin.config.getBoolean("$base.enabled", true)

        val toggleSection = icon(
            if (enabled) Material.LIME_DYE else Material.RED_DYE,
            "<yellow>Section Enabled:</yellow> <white>$enabled</white>",
            listOf("<gray>Toggle this entire section.</gray>")
        )
        inv.setItem(4, toggleSection)
        setConfirm(4, toggleSection) { p ->
            plugin.adminActions.setPerkSectionEnabled(p, sectionId, !enabled)
            plugin.gui.open(p, AdminPerkSectionMenu(plugin, sectionId))
        }

        val perksSec = plugin.config.getConfigurationSection("$base.perks")
        if (perksSec != null) {
            var slot = 10
            for (perkId in perksSec.getKeys(false)) {
                if (slot >= inv.size - 10) break
                val pBase = "$base.perks.$perkId"
                val perkEnabled = plugin.config.getBoolean("$pBase.enabled", true)
                val name = plugin.config.getString("$pBase.display_name") ?: "<white>$perkId</white>"
                val lore = plugin.config.getStringList("$pBase.lore")

                val item = icon(
                    if (perkEnabled) Material.LIME_DYE else Material.RED_DYE,
                    "$name <gray>(${if (perkEnabled) "ON" else "OFF"})</gray>",
                    lore + listOf("", "<gray>Click to toggle this perk.</gray>")
                )
                inv.setItem(slot, item)
                set(slot, item) { p ->
                    overrideClickSound(UiClickSound.CONFIRM)
                    plugin.adminActions.setPerkEnabled(p, sectionId, perkId, !perkEnabled)
                    plugin.gui.open(p, AdminPerkSectionMenu(plugin, sectionId))
                }

                slot++
            }
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(49, back)
        set(49, back) { p -> plugin.gui.open(p, AdminPerkCatalogMenu(plugin)) }
    }
}
