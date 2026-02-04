package mayorSystem.economy

import mayorSystem.cloud.CommandContext
import mayorSystem.economy.ui.AdminEconomyMenu
import mayorSystem.economy.ui.AdminSettingsSellBonusesMenu
import mayorSystem.security.Perms
import org.bukkit.entity.Player
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.StringParser.stringParser

class EconomyCommands(private val ctx: CommandContext) {
    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm

        ctx.registerMenuRoute(
            literals = listOf("admin", "economy"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_ECONOMY_EDIT),
                Permission.of(Perms.ADMIN_SETTINGS_EDIT)
            ),
            menuFactory = { AdminEconomyMenu(plugin) }
        )

        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "sell_all_stack"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_ECONOMY_EDIT),
                Permission.of(Perms.ADMIN_SETTINGS_EDIT)
            ),
            menuFactory = { AdminSettingsSellBonusesMenu(plugin) }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("sell_all_stack")
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_ECONOMY_EDIT),
                        Permission.of(Perms.ADMIN_SETTINGS_EDIT)
                    )
                )
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val value = ctx.parseBool(command.get("value")) ?: run {
                        ctx.msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "sell_bonus.all_bonus_stack", value)
                    ctx.msg(admin, "admin.settings.sell_all_stack_set", mapOf("value" to value.toString()))
                }
        )
    }
}

