package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminSettingsGeneralMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>⚙ Settings: General</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        // Intentionally no border/filler.

        val s = plugin.settings

        val enabledItem = icon(
            if (s.enabled) Material.LIME_DYE else Material.RED_DYE,
            "<yellow>Plugin Enabled:</yellow> <white>${s.enabled}</white>",
            listOf("<gray>Toggle the entire system.</gray>")
        )
        inv.setItem(22, enabledItem)
        setConfirm(22, enabledItem) { p, _ ->
            plugin.adminActions.updateConfig(p, "enabled", !s.enabled)
            plugin.gui.open(p, AdminSettingsGeneralMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(43, back)
        set(43, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }
}
