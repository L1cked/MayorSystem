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
        border(inv)

        val s = plugin.settings

        val enabledItem = icon(
            if (s.enabled) Material.LIME_DYE else Material.RED_DYE,
            "<yellow>Plugin Enabled:</yellow> <white>${s.enabled}</white>",
            listOf("<gray>Toggle the entire system.</gray>")
        )
        inv.setItem(22, enabledItem)
        setConfirm(22, enabledItem) { p, _ ->
            plugin.adminActions.updateSettingsConfig(p, "enabled", !s.enabled)
            plugin.gui.open(p, AdminSettingsGeneralMenu(plugin))
        }

        val pauseItem = icon(
            if (s.pauseEnabled) Material.YELLOW_DYE else Material.GRAY_DYE,
            "<yellow>Pause Schedule:</yellow> <white>${s.pauseEnabled}</white>",
            listOf("<gray>Freeze elections/term timers.</gray>")
        )
        inv.setItem(20, pauseItem)
        setConfirm(20, pauseItem) { p, _ ->
            plugin.adminActions.updateSettingsConfig(p, "pause.enabled", !s.pauseEnabled)
            plugin.gui.open(p, AdminSettingsGeneralMenu(plugin))
        }

        val resetItem = icon(
            Material.BARRIER,
            "<red>Reset Elections</red>",
            listOf(
                "<gray>Wipes term data + mayor.</gray>",
                "<gray>Resets the term counter to 0.</gray>",
                "<dark_gray>Click to confirm.</dark_gray>"
            )
        )
        inv.setItem(24, resetItem)
        setConfirm(24, resetItem) { p, _ ->
            plugin.gui.open(p, AdminResetElectionConfirmMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

}
