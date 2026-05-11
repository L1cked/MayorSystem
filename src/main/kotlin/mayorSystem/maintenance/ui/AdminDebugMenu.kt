package mayorSystem.maintenance.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminMenu
import mayorSystem.ui.Menu
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

class AdminDebugMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#00c6ff:#0072ff>Maintenance</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val canReload = player.hasPermission(Perms.ADMIN_MAINTENANCE_RELOAD)
                || player.hasPermission(Perms.ADMIN_SETTINGS_RELOAD)
        val canDebug = player.hasPermission(Perms.ADMIN_MAINTENANCE_DEBUG)

        if (!canReload && !canDebug) {
            inv.setItem(
                13,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have access to maintenance tools.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray><- Back</gray>")
            inv.setItem(BACK_SLOT, back)
            set(BACK_SLOT, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
            return
        }

        val tools = mutableListOf<MenuAction>()

        if (canReload) {
            val item = icon(
                Material.NETHER_STAR,
                "<yellow>Reload</yellow>",
                listOf("<gray>Reload config and data store.</gray>")
            )
            tools += MenuAction(item, confirm = true) { p ->
                plugin.scope.launch(plugin.mainDispatcher) {
                    dispatchResult(p, plugin.adminUseCases.settings.reload(p), denyOnNonSuccess = true)
                    plugin.gui.open(p, AdminDebugMenu(plugin))
                }
            }
        }

        if (canDebug) {
            val item = icon(
                Material.COMPASS,
                "<yellow>Refresh Offline Cache</yellow>",
                listOf("<gray>Rebuilds cached offline players.</gray>")
            )
            tools += MenuAction(item) { p ->
                plugin.offlinePlayers.refreshAsync()
                plugin.messages.msg(p, "admin.system.offline_cache_refreshed")
                plugin.gui.open(p, AdminDebugMenu(plugin))
            }
        }

        if (canDebug) {
            val item = icon(
                Material.BARRIER,
                "<red>Reset Elections</red>",
                listOf(
                    "<gray>Wipes term data and mayor.</gray>",
                    "<dark_gray>Requires confirmation.</dark_gray>"
                )
            )
            tools += MenuAction(item) { p -> plugin.gui.open(p, AdminResetElectionConfirmMenu(plugin)) }
        }

        centeredSlots(tools.size).forEachIndexed { index, slot ->
            val action = tools[index]
            inv.setItem(slot, action.item)
            if (action.confirm) {
                setConfirm(slot, action.item, action.onClick)
            } else {
                set(slot, action.item, action.onClick)
            }
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
        val confirm: Boolean = false,
        val onClick: (Player) -> Unit
    )

    private companion object {
        const val BACK_SLOT = 18
    }
}

