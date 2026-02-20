package mayorSystem.monitoring

import mayorSystem.cloud.CommandContext
import mayorSystem.monitoring.ui.AdminAuditMenu
import mayorSystem.monitoring.ui.AdminHealthMenu
import mayorSystem.monitoring.ui.AdminMonitoringMenu
import mayorSystem.security.Perms
import org.incendo.cloud.permission.Permission

class MonitoringCommands(private val ctx: CommandContext) {
    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm

        ctx.registerMenuRoute(
            literals = listOf("admin", "monitoring"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_AUDIT_VIEW),
                Permission.of(Perms.ADMIN_HEALTH_VIEW)
            ),
            menuFactory = { AdminMonitoringMenu(plugin) }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("audit")
                .permission(Perms.ADMIN_AUDIT_VIEW)
                .handler { command ->
                    val sender = command.sender().source()
                    ctx.withPlayer(sender) { admin ->
                        plugin.gui.open(admin, AdminAuditMenu(plugin))
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("health")
                .permission(Perms.ADMIN_HEALTH_VIEW)
                .handler { command ->
                    val sender = command.sender().source()
                    ctx.withPlayer(sender) { admin ->
                        plugin.gui.open(admin, AdminHealthMenu(plugin))
                    }
                }
        )
    }
}

