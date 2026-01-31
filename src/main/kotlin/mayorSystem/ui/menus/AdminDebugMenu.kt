package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminDebugMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#00c6ff:#0072ff>Debug Tools</gradient>")
    override val rows: Int = 4

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val canAudit = player.hasPermission(Perms.ADMIN_AUDIT_VIEW)
                || player.hasPermission(Perms.LEGACY_ADMIN_AUDIT)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
        val canHealth = player.hasPermission(Perms.ADMIN_HEALTH_VIEW)
                || player.hasPermission(Perms.LEGACY_ADMIN_HEALTH)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
        val canReload = player.hasPermission(Perms.ADMIN_SETTINGS_RELOAD)
                || player.hasPermission(Perms.LEGACY_ADMIN_SETTINGS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
        val canReset = player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
                || player.hasPermission(Perms.LEGACY_ADMIN_SETTINGS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)

        if (!canAudit && !canHealth && !canReload && !canReset) {
            inv.setItem(
                13,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have access to debug tools.</gray>")
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

        if (canAudit) {
            val slot = nextSlot()
            if (slot != null) {
                val item = icon(
                    Material.PAPER,
                    "<white>Audit Log</white>",
                    listOf("<gray>Review and export admin actions.</gray>")
                )
                inv.setItem(slot, item)
                set(slot, item) { p -> plugin.gui.open(p, AdminAuditMenu(plugin)) }
            }
        }

        if (canHealth) {
            val slot = nextSlot()
            if (slot != null) {
                val item = icon(
                    Material.SPYGLASS,
                    "<green>Health Check</green>",
                    listOf("<gray>Run diagnostics and export reports.</gray>")
                )
                inv.setItem(slot, item)
                set(slot, item) { p -> plugin.gui.open(p, AdminHealthMenu(plugin)) }
            }
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
                    plugin.adminActions.reload(p)
                    plugin.messages.msg(p, "admin.settings.reloaded")
                    plugin.gui.open(p, AdminDebugMenu(plugin))
                }
            }
        }

        if (canReload) {
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

        if (canReset) {
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
