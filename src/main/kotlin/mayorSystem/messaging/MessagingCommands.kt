package mayorSystem.messaging

import mayorSystem.cloud.CommandContext
import mayorSystem.messaging.ui.AdminBroadcastSettingsMenu
import mayorSystem.messaging.ui.AdminMessagingMenu
import mayorSystem.messaging.ui.AdminSettingsChatPromptsMenu
import mayorSystem.security.Perms
import org.bukkit.entity.Player
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.IntegerParser.integerParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider

class MessagingCommands(private val ctx: CommandContext) {
    private val chatPromptKeySuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        listOf("bio", "title", "description")
    )

    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm

        ctx.registerMenuRoute(
            literals = listOf("admin", "messaging"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_MESSAGING_EDIT),
                Permission.of(Perms.ADMIN_SETTINGS_EDIT)
            ),
            menuFactory = { AdminMessagingMenu(plugin) }
        )

        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "chat_prompts"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_MESSAGING_EDIT),
                Permission.of(Perms.ADMIN_SETTINGS_EDIT)
            ),
            menuFactory = { AdminSettingsChatPromptsMenu(plugin) }
        )

        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "broadcasts"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_MESSAGING_EDIT),
                Permission.of(Perms.ADMIN_SETTINGS_EDIT)
            ),
            menuFactory = { AdminBroadcastSettingsMenu(plugin) }
        )

        // Settings commands
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("chat_prompts")
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_MESSAGING_EDIT),
                        Permission.of(Perms.ADMIN_SETTINGS_EDIT)
                    )
                )
                .senderType(PlayerSource::class.java)
                .required("field", stringParser(), chatPromptKeySuggestions)
                .required("value", integerParser())
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val field = command.get<String>("field").lowercase()
                    val value = command.get<Int>("value").coerceIn(1, 500)
                    val path = when (field) {
                        "bio" -> "ux.chat_prompts.max_length.bio"
                        "title" -> "ux.chat_prompts.max_length.title"
                        "description" -> "ux.chat_prompts.max_length.description"
                        else -> null
                    }
                    if (path == null) {
                        ctx.msg(admin, "admin.settings.chat_prompts_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, path, value)
                    ctx.msg(admin, "admin.settings.chat_prompts_set", mapOf("field" to field, "value" to value.toString()))
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("chat_prompt_timeout")
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_MESSAGING_EDIT),
                        Permission.of(Perms.ADMIN_SETTINGS_EDIT)
                    )
                )
                .senderType(PlayerSource::class.java)
                .required("value", integerParser())
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val value = command.get<Int>("value").coerceAtLeast(30)
                    plugin.adminActions.updateSettingsConfig(admin, "ux.chat_prompt_timeout_seconds", value)
                    ctx.msg(admin, "admin.settings.chat_prompt_timeout_set", mapOf("value" to value.toString()))
                }
        )
    }
}

