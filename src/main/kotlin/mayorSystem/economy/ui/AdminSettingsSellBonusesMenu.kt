package mayorSystem.economy.ui

import mayorSystem.MayorPlugin
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminSettingsSellBonusesMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>Settings: Sell Bonuses</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val s = plugin.settings

        val sellAllStackItem = icon(
            if (s.sellAllBonusStacks) Material.LIME_DYE else Material.RED_DYE,
            "<yellow>All Sell Bonus Stacks:</yellow> <white>${s.sellAllBonusStacks}</white>",
            listOf(
                "<gray>true = All bonus stacks with category perks.</gray>",
                "<gray>false = Only items without category perks.</gray>"
            )
        )
        inv.setItem(13, sellAllStackItem)
        setConfirm(13, sellAllStackItem) { p, _ ->
            plugin.adminActions.updateSettingsConfig(p, "sell_bonus.all_bonus_stack", !s.sellAllBonusStacks)
            plugin.gui.open(p, AdminSettingsSellBonusesMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }
}

