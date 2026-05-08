package mayorSystem.perks.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

class AdminPerksMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#f7971e:#ffd200>Perks Management</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val canCatalog = player.hasPermission(Perms.ADMIN_PERKS_CATALOG)
        val canRequests = player.hasPermission(Perms.ADMIN_PERKS_REQUESTS)
        val canRefresh = player.hasPermission(Perms.ADMIN_PERKS_REFRESH)

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
            inv.setItem(BACK_SLOT, back)
            set(BACK_SLOT, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
            return
        }

        val tools = mutableListOf<MenuAction>()
        if (canCatalog) {
            val catalog = icon(
                Material.CHEST,
                "<gold>Perk Catalog</gold>",
                listOf("<gray>Enable/disable sections and perks.</gray>")
            )
            tools += MenuAction(catalog) { p -> plugin.gui.open(p, AdminPerkCatalogMenu(plugin)) }
        }

        if (canRequests) {
            val requests = icon(
                Material.BOOK,
                "<yellow>Custom Requests</yellow>",
                listOf("<gray>Approve/deny custom perk requests.</gray>")
            )
            tools += MenuAction(requests) { p -> plugin.gui.open(p, AdminPerkRequestsMenu(plugin)) }
        }

        if (canRefresh) {
            val refresh = icon(
                Material.NETHER_STAR,
                "<green>Refresh Perks</green>",
                listOf("<gray>Re-apply active perk effects.</gray>")
            )
            tools += MenuAction(refresh) { p -> plugin.gui.open(p, AdminPerkRefreshMenu(plugin)) }
        }

        centeredSlots(tools.size).forEachIndexed { index, slot ->
            val action = tools[index]
            inv.setItem(slot, action.item)
            set(slot, action.item, action.onClick)
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(BACK_SLOT, back)
        set(BACK_SLOT, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
    }

    private fun centeredSlots(count: Int): List<Int> = when (count) {
        1 -> listOf(13)
        2 -> listOf(12, 14)
        else -> listOf(11, 13, 15).take(count)
    }

    private data class MenuAction(
        val item: ItemStack,
        val onClick: (Player) -> Unit
    )

    private companion object {
        const val BACK_SLOT = 18
    }
}

