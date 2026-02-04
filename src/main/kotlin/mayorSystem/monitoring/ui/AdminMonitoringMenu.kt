package mayorSystem.monitoring.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminMonitoringMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#00c6ff:#0072ff>Monitoring</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val canAudit = player.hasPermission(Perms.ADMIN_AUDIT_VIEW)
        val canHealth = player.hasPermission(Perms.ADMIN_HEALTH_VIEW)

        if (!canAudit && !canHealth) {
            inv.setItem(
                13,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have access to monitoring tools.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray><- Back</gray>")
            inv.setItem(18, back)
            set(18, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
            return
        }

        if (canAudit) {
            val audit = icon(
                Material.PAPER,
                "<white>Audit Log</white>",
                listOf("<gray>Review and export admin actions.</gray>")
            )
            inv.setItem(11, audit)
            set(11, audit) { p -> plugin.gui.open(p, AdminAuditMenu(plugin)) }
        }

        if (canHealth) {
            val health = icon(
                Material.SPYGLASS,
                "<green>Health Check</green>",
                listOf("<gray>Run diagnostics and export reports.</gray>")
            )
            inv.setItem(15, health)
            set(15, health) { p -> plugin.gui.open(p, AdminHealthMenu(plugin)) }
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
    }
}

