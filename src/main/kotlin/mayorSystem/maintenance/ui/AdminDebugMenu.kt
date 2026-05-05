package mayorSystem.maintenance.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import kotlinx.coroutines.launch

class AdminDebugMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#00c6ff:#0072ff>Maintenance</gradient>")
    override val rows: Int = 4

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
            inv.setItem(27, back)
            set(27, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
            return
        }

        val slots = listOf(10, 12, 14, 16, 20, 22, 24)
        var slotIndex = 0
        fun nextSlot(): Int? {
            if (slotIndex >= slots.size) return null
            val slot = slots[slotIndex]
            slotIndex++
            return slot
        }

        if (canReload) {
            val slot = nextSlot()
            if (slot != null) {
                val item = icon(
                    Material.NETHER_STAR,
                    "<yellow>Reload</yellow>",
                    listOf("<gray>Reload config and data store.</gray>")
                )
                inv.setItem(slot, item)
                setConfirm(slot, item) { p, _ ->
                    plugin.scope.launch(plugin.mainDispatcher) {
                        dispatchResult(p, plugin.adminActions.reload(p), denyOnNonSuccess = true)
                        plugin.gui.open(p, AdminDebugMenu(plugin))
                    }
                }
            }
        }

        if (canDebug) {
            val slot = nextSlot()
            if (slot != null) {
                val item = icon(
                    Material.COMPASS,
                    "<yellow>Refresh Offline Cache</yellow>",
                    listOf("<gray>Rebuilds cached offline players.</gray>")
                )
                inv.setItem(slot, item)
                set(slot, item) { p ->
                    plugin.offlinePlayers.refreshAsync()
                    plugin.messages.msg(p, "admin.system.offline_cache_refreshed")
                    plugin.gui.open(p, AdminDebugMenu(plugin))
                }
            }
        }

        if (canDebug) {
            val slot = nextSlot()
            if (slot != null) {
                val item = icon(
                    Material.BARRIER,
                    "<red>Reset Elections</red>",
                    listOf(
                        "<gray>Wipes term data and mayor.</gray>",
                        "<dark_gray>Requires confirmation.</dark_gray>"
                    )
                )
                inv.setItem(slot, item)
                set(slot, item) { p -> plugin.gui.open(p, AdminResetElectionConfirmMenu(plugin)) }
            }
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(27, back)
        set(27, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
    }
}

