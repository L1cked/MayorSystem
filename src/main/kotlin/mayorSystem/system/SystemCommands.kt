package mayorSystem.system

import mayorSystem.cloud.CommandContext
import mayorSystem.config.SystemGateOption
import mayorSystem.security.Perms
import mayorSystem.showcase.ShowcaseMode
import mayorSystem.showcase.ShowcaseTarget
import mayorSystem.system.ui.AdminMenu
import mayorSystem.system.ui.AdminSettingsEnableOptionsMenu
import mayorSystem.system.ui.AdminSettingsGeneralMenu
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.system.ui.AdminSettingsPauseOptionsMenu
import mayorSystem.system.ui.AdminDisplayMenu
import mayorSystem.system.ui.AdminSettingsMayorGroupMenu
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider
import kotlinx.coroutines.launch

class SystemCommands(private val ctx: CommandContext) {
    private val gateOptionSuggestions = SuggestionProvider.suggestingStrings<CommandSender>(
        SystemGateOption.values().map { it.name }
    )
    private val showcaseModeSuggestions = SuggestionProvider.suggestingStrings<CommandSender>(
        listOf("switching", "individual")
    )
    private val groupNameRegex = Regex("^[A-Za-z0-9_.-]+$")

    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm

        // /mayor admin -> admin panel
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .permission(Perms.ADMIN_PANEL_OPEN)
                .handler { command ->
                    val sender = command.sender()
                    ctx.withPlayer(sender) { admin ->
                        plugin.gui.open(admin, AdminMenu(plugin))
                    }
                }
        )

        // /mayor admin system -> system menu
        ctx.registerMenuRoute(
            literals = listOf("admin", "system"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_SYSTEM_TOGGLE),
                Permission.of(Perms.ADMIN_SETTINGS_EDIT)
            ),
            menuFactory = { AdminSettingsGeneralMenu(plugin) }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("system")
                .literal("toggle")
                .permission(Perms.ADMIN_SYSTEM_TOGGLE)
                .senderType(Player::class.java)
                .handler { command ->
                    val admin = command.sender()
                    togglePublicAccess(admin)
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("system")
                .literal("refresh_offline_cache")
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_MAINTENANCE_DEBUG),
                        Permission.of(Perms.ADMIN_SETTINGS_RELOAD)
                    )
                )
                .senderType(Player::class.java)
                .handler { command ->
                    val admin = command.sender()
                    plugin.offlinePlayers.refreshAsync()
                    ctx.msg(admin, "admin.system.offline_cache_refreshed")
                }
        )

        // NPC management
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("npc")
                .literal("spawn")
                .permission(Perms.ADMIN_NPC_MAYOR)
                .senderType(Player::class.java)
                .handler { command ->
                    val admin = command.sender()
                    if (!allowShowcaseTarget(admin, ShowcaseTarget.NPC)) return@handler
                    plugin.mayorNpc.spawnHere(admin)
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("npc")
                .literal("remove")
                .permission(Perms.ADMIN_NPC_MAYOR)
                .senderType(Player::class.java)
                .handler { command ->
                    val admin = command.sender()
                    if (!allowShowcaseTarget(admin, ShowcaseTarget.NPC)) return@handler
                    plugin.mayorNpc.remove(admin)
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("npc")
                .literal("update")
                .permission(Perms.ADMIN_NPC_MAYOR)
                .senderType(Player::class.java)
                .handler { command ->
                    val admin = command.sender()
                    if (!allowShowcaseTarget(admin, ShowcaseTarget.NPC)) return@handler
                    plugin.mayorNpc.forceUpdate(admin)
                }
        )

        // Hologram management (DecentHolograms / FancyHolograms)
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("hologram")
                .literal("spawn")
                .permission(Perms.ADMIN_HOLOGRAM_LEADERBOARD)
                .senderType(Player::class.java)
                .handler { command ->
                    val admin = command.sender()
                    if (!allowShowcaseTarget(admin, ShowcaseTarget.HOLOGRAM)) return@handler
                    plugin.leaderboardHologram.spawnHere(admin)
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("hologram")
                .literal("remove")
                .permission(Perms.ADMIN_HOLOGRAM_LEADERBOARD)
                .senderType(Player::class.java)
                .handler { command ->
                    val admin = command.sender()
                    if (!allowShowcaseTarget(admin, ShowcaseTarget.HOLOGRAM)) return@handler
                    plugin.leaderboardHologram.remove(admin)
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("hologram")
                .literal("update")
                .permission(Perms.ADMIN_HOLOGRAM_LEADERBOARD)
                .senderType(Player::class.java)
                .handler { command ->
                    val admin = command.sender()
                    if (!allowShowcaseTarget(admin, ShowcaseTarget.HOLOGRAM)) return@handler
                    plugin.leaderboardHologram.forceUpdate(admin)
                }
        )

        // Showcase mode (switching vs individual)
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("display")
                .literal("mode")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(Player::class.java)
                .required("value", stringParser(), showcaseModeSuggestions)
                .handler { command ->
                    val admin = command.sender()
                    val raw = command.get<String>("value")
                    val mode = when (raw.trim().lowercase()) {
                        "switching" -> ShowcaseMode.SWITCHING
                        "individual" -> ShowcaseMode.INDIVIDUAL
                        else -> null
                    }
                    if (mode == null) {
                        ctx.msg(admin, "admin.showcase.mode_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        val result = plugin.adminActions.updateConfig(
                            admin,
                            "showcase.mode",
                            mode.name,
                            reload = false,
                            successKey = "admin.showcase.mode_set",
                            successPlaceholders = mapOf("mode" to mode.name)
                        )
                        if (result.isSuccess) {
                            plugin.showcase.sync()
                        }
                        ctx.dispatch(admin, result)
                    }
                }
        )

        // Legacy settings hub
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_SETTINGS_EDIT),
                Permission.of(Perms.ADMIN_SYSTEM_TOGGLE),
                Permission.of(Perms.ADMIN_GOVERNANCE_EDIT),
                Permission.of(Perms.ADMIN_MESSAGING_EDIT),
                Permission.of(Perms.ADMIN_PERKS_CATALOG)
            ),
            menuFactory = { AdminSettingsMenu(plugin) }
        )

        // Menu shortcuts
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "enabled"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsGeneralMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "public_enabled"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsGeneralMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "pause_enabled"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsGeneralMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "mayor_group"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsMayorGroupMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "enable_options"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsEnableOptionsMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "pause_options"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsPauseOptionsMenu(plugin) }
        )

        // Display (NPC + Hologram) menu
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "display"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_SETTINGS_EDIT),
                Permission.of(Perms.ADMIN_NPC_MAYOR),
                Permission.of(Perms.ADMIN_HOLOGRAM_LEADERBOARD)
            ),
            menuFactory = { AdminDisplayMenu(plugin) }
        )

        ctx.registerMenuRoute(
            literals = listOf("admin", "display"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_SETTINGS_EDIT),
                Permission.of(Perms.ADMIN_NPC_MAYOR),
                Permission.of(Perms.ADMIN_HOLOGRAM_LEADERBOARD)
            ),
            menuFactory = { AdminDisplayMenu(plugin) }
        )

        // Settings commands
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("enabled")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(Player::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender()
                    val value = ctx.parseBool(command.get("value")) ?: run {
                        ctx.msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "enabled",
                                value,
                                "admin.settings.enabled_set",
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
                .literal("public_enabled")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(Player::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender()
                    val value = ctx.parseBool(command.get("value")) ?: run {
                        ctx.msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    if (!plugin.settings.enabled) {
                        ctx.msg(admin, "admin.system.master_off")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "public_enabled",
                                value,
                                "admin.settings.public_enabled_set",
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
                .literal("pause_enabled")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(Player::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender()
                    val value = ctx.parseBool(command.get("value")) ?: run {
                        ctx.msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "pause.enabled",
                                value,
                                "admin.settings.pause_enabled_set",
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
                .literal("mayor_group_enabled")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(Player::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender()
                    val value = ctx.parseBool(command.get("value")) ?: run {
                        ctx.msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "title.username_group_enabled",
                                value,
                                "admin.settings.mayor_group_enabled_set",
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
                .literal("mayor_group")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(Player::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender()
                    val value = command.get<String>("value").trim()
                    if (value.isBlank() || !groupNameRegex.matches(value)) {
                        ctx.msg(admin, "admin.settings.mayor_group_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "title.username_group",
                                value,
                                "admin.settings.mayor_group_set",
                                mapOf("value" to value)
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("enable_options")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(Player::class.java)
                .required("option", stringParser(), gateOptionSuggestions)
                .handler { command ->
                    val admin = command.sender()
                    val raw = command.get<String>("option")
                    val opt = SystemGateOption.parse(raw)
                    if (opt == null) {
                        ctx.msg(admin, "admin.settings.options_invalid", mapOf("options" to ctx.optionListString()))
                        return@handler
                    }
                    val enabled = if (ctx.currentGateOptions("enable_options").contains(opt)) "DISABLED" else "ENABLED"
                    val next = ctx.currentGateOptions("enable_options").toMutableSet().apply {
                        if (contains(opt)) remove(opt) else add(opt)
                    }.map { it.name }.sorted()
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "enable_options",
                                next,
                                "admin.settings.enable_options_set",
                                mapOf("option" to opt.name, "state" to enabled)
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("pause_options")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(Player::class.java)
                .required("option", stringParser(), gateOptionSuggestions)
                .handler { command ->
                    val admin = command.sender()
                    val raw = command.get<String>("option")
                    val opt = SystemGateOption.parse(raw)
                    if (opt == null) {
                        ctx.msg(admin, "admin.settings.options_invalid", mapOf("options" to ctx.optionListString()))
                        return@handler
                    }
                    val enabled = if (ctx.currentGateOptions("pause.options").contains(opt)) "DISABLED" else "ENABLED"
                    val next = ctx.currentGateOptions("pause.options").toMutableSet().apply {
                        if (contains(opt)) remove(opt) else add(opt)
                    }.map { it.name }.sorted()
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "pause.options",
                                next,
                                "admin.settings.pause_options_set",
                                mapOf("option" to opt.name, "state" to enabled)
                            )
                        )
                    }
                }
        )
    }

    private fun togglePublicAccess(admin: Player) {
        val masterEnabled = ctx.plugin.settings.enabled
        if (!masterEnabled) {
            ctx.msg(admin, "admin.system.master_off")
            return
        }

        val current = ctx.plugin.settings.publicEnabled
        val next = !current
        ctx.plugin.scope.launch(ctx.plugin.mainDispatcher) {
            val result = ctx.plugin.adminActions.updateSettingsConfig(
                admin,
                "public_enabled",
                next,
                if (next) "admin.system.public_enabled" else "admin.system.public_disabled"
            )
            ctx.dispatch(admin, result)
        }
    }

    private fun allowShowcaseTarget(admin: Player, target: ShowcaseTarget): Boolean {
        val mode = ctx.plugin.showcase.mode()
        if (mode != ShowcaseMode.SWITCHING) return true
        val electionOpen = ctx.plugin.showcase.electionOpenNow()
        val desired = ctx.plugin.showcase.desiredTarget(electionOpen)
        if (desired == target) return true
        ctx.msg(admin, "admin.showcase.target_locked", mapOf("target" to desired.name))
        return false
    }
}

