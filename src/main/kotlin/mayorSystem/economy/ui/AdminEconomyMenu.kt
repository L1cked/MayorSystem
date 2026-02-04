package mayorSystem.economy.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminEconomyMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#f7971e:#ffd200>Economy Settings</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val canEdit = player.hasPermission(Perms.ADMIN_ECONOMY_EDIT)
                || player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
                || player.hasPermission(Perms.LEGACY_ADMIN_SETTINGS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)

        if (!canEdit) {
            inv.setItem(
                13,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have access to economy settings.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray><- Back</gray>")
            inv.setItem(18, back)
            set(18, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
            return
        }

        val sellBonuses = icon(
            Material.GOLD_INGOT,
            "<yellow>Sell Bonuses</yellow>",
            listOf("<gray>Configure /sell bonus stacking.</gray>")
        )
        inv.setItem(13, sellBonuses)
        set(13, sellBonuses) { p -> plugin.gui.open(p, AdminSettingsSellBonusesMenu(plugin)) }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }
}

