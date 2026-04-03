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
import kotlinx.coroutines.launch

class MessagingCommands(private val ctx: CommandContext) {
    private val chatPromptKeySuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        listOf("bio", "title", "description")
    )
    private val broadcastEventSuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        listOf("vote", "apply")
    )
    private val broadcastModeSuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        listOf("disabled", "chat", "title", "both")
    )
    private val lifecycleModeSuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        listOf("chat", "title", "both")
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
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                path,
                                value,
                                "admin.settings.chat_prompts_set",
                                mapOf("field" to field, "value" to value.toString())
                            )
                        )
                    }
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
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "ux.chat_prompt_timeout_seconds",
                                value,
                                "admin.settings.chat_prompt_timeout_set",
                                mapOf("value" to value.toString())
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("broadcasts")
                .literal("enabled")
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_MESSAGING_EDIT),
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
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "election.broadcast.enabled",
                                value,
                                "admin.settings.reloaded"
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("broadcasts")
                .literal("mode")
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_MESSAGING_EDIT),
                        Permission.of(Perms.ADMIN_SETTINGS_EDIT)
                    )
                )
                .senderType(PlayerSource::class.java)
                .required("value", stringParser(), lifecycleModeSuggestions)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val mode = parseMode(command.get("value"), allowDisabled = false) ?: run {
                        ctx.msg(admin, "admin.settings.broadcast_mode_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "election.broadcast.mode",
                                mode,
                                "admin.settings.reloaded"
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("broadcasts")
                .literal("event")
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_MESSAGING_EDIT),
                        Permission.of(Perms.ADMIN_SETTINGS_EDIT)
                    )
                )
                .senderType(PlayerSource::class.java)
                .required("event", stringParser(), broadcastEventSuggestions)
                .required("mode", stringParser(), broadcastModeSuggestions)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val event = command.get<String>("event").lowercase()
                    val mode = parseMode(command.get("mode"), allowDisabled = true) ?: run {
                        ctx.msg(admin, "admin.settings.broadcast_mode_invalid")
                        return@handler
                    }
                    val path = when (event) {
                        "vote" -> "election.broadcast.vote.mode"
                        "apply" -> "election.broadcast.apply.mode"
                        else -> null
                    }
                    if (path == null) {
                        ctx.msg(admin, "admin.settings.broadcast_event_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                path,
                                mode,
                                "admin.settings.reloaded"
                            )
                        )
                    }
                }
        )
    }

    private fun parseMode(raw: String, allowDisabled: Boolean): String? {
        return when (raw.trim().uppercase()) {
            "CHAT" -> "CHAT"
            "TITLE" -> "TITLE"
            "BOTH" -> "BOTH"
            "DISABLED", "NONE", "OFF" -> if (allowDisabled) "DISABLED" else null
            else -> null
        }
    }
}

