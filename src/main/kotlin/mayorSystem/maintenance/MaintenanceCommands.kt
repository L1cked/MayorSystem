package mayorSystem.maintenance

import mayorSystem.cloud.CommandContext
import mayorSystem.maintenance.ui.AdminDebugMenu
import mayorSystem.security.Perms
import org.incendo.cloud.permission.Permission

class MaintenanceCommands(private val ctx: CommandContext) {
    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm

        ctx.registerMenuRoute(
            literals = listOf("admin", "maintenance"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_MAINTENANCE_RELOAD),
                Permission.of(Perms.ADMIN_MAINTENANCE_DEBUG),
                Permission.of(Perms.ADMIN_SETTINGS_RELOAD)
            ),
            menuFactory = { AdminDebugMenu(plugin) }
        )

        // Legacy debug menu
        ctx.registerMenuRoute(
            literals = listOf("admin", "debug"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_MAINTENANCE_RELOAD),
                Permission.of(Perms.ADMIN_MAINTENANCE_DEBUG),
                Permission.of(Perms.ADMIN_SETTINGS_RELOAD)
            ),
            menuFactory = { AdminDebugMenu(plugin) }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("reload")
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_MAINTENANCE_RELOAD),
                        Permission.of(Perms.ADMIN_SETTINGS_RELOAD)
                    )
                )
                .handler { command ->
                    val sender = command.sender().source()
                    plugin.adminActions.reload(sender as? org.bukkit.entity.Player)
                    ctx.msg(sender, "admin.settings.reloaded")
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reload")
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_MAINTENANCE_RELOAD),
                        Permission.of(Perms.ADMIN_SETTINGS_RELOAD)
                    )
                )
                .handler { command ->
                    val sender = command.sender().source()
                    plugin.adminActions.reload(sender as? org.bukkit.entity.Player)
                    ctx.msg(sender, "admin.settings.reloaded")
                }
        )
    }
}

