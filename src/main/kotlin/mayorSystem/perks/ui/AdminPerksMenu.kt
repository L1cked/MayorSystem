package mayorSystem.perks.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminPerksMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#f7971e:#ffd200>Perks Management</gradient>")
    override val rows: Int = 4

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val canCatalog = player.hasPermission(Perms.ADMIN_PERKS_CATALOG)
                || player.hasPermission(Perms.LEGACY_ADMIN_PERKS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
        val canRequests = player.hasPermission(Perms.ADMIN_PERKS_REQUESTS)
                || player.hasPermission(Perms.LEGACY_ADMIN_PERKS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
        val canRefresh = player.hasPermission(Perms.ADMIN_PERKS_REFRESH)
                || player.hasPermission(Perms.LEGACY_ADMIN_PERKS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)

        if (!canCatalog && !canRequests && !canRefresh) {
            inv.setItem(
                13,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have access to perks tools.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray><- Back</gray>")
            inv.setItem(27, back)
            set(27, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
            return
        }

        if (canCatalog) {
            val catalog = icon(
                Material.CHEST,
                "<gold>Perk Catalog</gold>",
                listOf("<gray>Enable/disable sections and perks.</gray>")
            )
            inv.setItem(11, catalog)
            set(11, catalog) { p -> plugin.gui.open(p, AdminPerkCatalogMenu(plugin)) }
        }

        if (canRequests) {
            val requests = icon(
                Material.BOOK,
                "<yellow>Custom Requests</yellow>",
                listOf("<gray>Approve/deny custom perk requests.</gray>")
            )
            inv.setItem(13, requests)
            set(13, requests) { p -> plugin.gui.open(p, AdminPerkRequestsMenu(plugin)) }
        }

        if (canRefresh) {
            val refresh = icon(
                Material.NETHER_STAR,
                "<green>Refresh Perks</green>",
                listOf("<gray>Re-apply active perk effects.</gray>")
            )
            inv.setItem(15, refresh)
            set(15, refresh) { p -> plugin.gui.open(p, AdminPerkRefreshMenu(plugin)) }
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(27, back)
        set(27, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
    }
}

