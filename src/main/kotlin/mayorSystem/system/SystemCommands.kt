package mayorSystem.system

import mayorSystem.cloud.CommandContext
import mayorSystem.config.SystemGateOption
import mayorSystem.security.Perms
import mayorSystem.showcase.ShowcaseMode
import mayorSystem.system.ui.AdminMenu
import mayorSystem.system.ui.AdminSettingsEnableOptionsMenu
import mayorSystem.system.ui.AdminSettingsGeneralMenu
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.system.ui.AdminSettingsPauseOptionsMenu
import mayorSystem.system.ui.AdminDisplayMenu
import org.bukkit.entity.Player
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider

class SystemCommands(private val ctx: CommandContext) {
    private val gateOptionSuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        SystemGateOption.values().map { it.name }
    )
    private val showcaseModeSuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        listOf("switching", "individual")
    )

    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm

        // /mayor admin -> admin panel
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .permission(Perms.ADMIN_PANEL_OPEN)
                .handler { command ->
                    val sender = command.sender().source()
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
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("system")
                .literal("toggle")
                .permission(Perms.ADMIN_SYSTEM_TOGGLE)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    togglePublicAccess(admin)
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("system")
                .literal("refresh_offline_cache")
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_MAINTENANCE_DEBUG),
                        Permission.of(Perms.ADMIN_SETTINGS_RELOAD)
                    )
                )
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    plugin.offlinePlayers.refreshAsync()
                    ctx.msg(admin, "admin.system.offline_cache_refreshed")
                }
        )

        // NPC management
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("npc")
                .literal("spawn")
                .permission(Perms.ADMIN_NPC_MAYOR)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    plugin.mayorNpc.spawnHere(admin)
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("npc")
                .literal("remove")
                .permission(Perms.ADMIN_NPC_MAYOR)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    plugin.mayorNpc.remove(admin)
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("npc")
                .literal("update")
                .permission(Perms.ADMIN_NPC_MAYOR)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    plugin.mayorNpc.forceUpdate(admin)
                }
        )

        // Hologram management (DecentHolograms)
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("hologram")
                .literal("spawn")
                .permission(Perms.ADMIN_HOLOGRAM_LEADERBOARD)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    plugin.leaderboardHologram.spawnHere(admin)
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("hologram")
                .literal("remove")
                .permission(Perms.ADMIN_HOLOGRAM_LEADERBOARD)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    plugin.leaderboardHologram.remove(admin)
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("hologram")
                .literal("update")
                .permission(Perms.ADMIN_HOLOGRAM_LEADERBOARD)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    plugin.leaderboardHologram.forceUpdate(admin)
                }
        )

        // Showcase mode (switching vs individual)
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("display")
                .literal("mode")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser(), showcaseModeSuggestions)
                .handler { command ->
                    val admin: Player = command.sender().source()
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
                    plugin.adminActions.updateConfig(admin, "showcase.mode", mode.name, reload = false)
                    plugin.showcase.sync()
                    ctx.msg(admin, "admin.showcase.mode_set", mapOf("mode" to mode.name))
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
                Permission.of(Perms.ADMIN_ECONOMY_EDIT),
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
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("enabled")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val value = ctx.parseBool(command.get("value")) ?: run {
                        ctx.msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "enabled", value)
                    ctx.msg(admin, "admin.settings.enabled_set", mapOf("value" to value.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("public_enabled")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val value = ctx.parseBool(command.get("value")) ?: run {
                        ctx.msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    if (!plugin.settings.enabled) {
                        ctx.msg(admin, "admin.system.master_off")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "public_enabled", value)
                    ctx.msg(admin, "admin.settings.public_enabled_set", mapOf("value" to value.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("pause_enabled")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val value = ctx.parseBool(command.get("value")) ?: run {
                        ctx.msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "pause.enabled", value)
                    ctx.msg(admin, "admin.settings.pause_enabled_set", mapOf("value" to value.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("enable_options")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("option", stringParser(), gateOptionSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val raw = command.get<String>("option")
                    val opt = SystemGateOption.parse(raw)
                    if (opt == null) {
                        ctx.msg(admin, "admin.settings.options_invalid", mapOf("options" to ctx.optionListString()))
                        return@handler
                    }
                    val enabled = ctx.toggleGateOption(admin, "enable_options", opt)
                    ctx.msg(
                        admin,
                        "admin.settings.enable_options_set",
                        mapOf("option" to opt.name, "state" to if (enabled) "ENABLED" else "DISABLED")
                    )
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("pause_options")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("option", stringParser(), gateOptionSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val raw = command.get<String>("option")
                    val opt = SystemGateOption.parse(raw)
                    if (opt == null) {
                        ctx.msg(admin, "admin.settings.options_invalid", mapOf("options" to ctx.optionListString()))
                        return@handler
                    }
                    val enabled = ctx.toggleGateOption(admin, "pause.options", opt)
                    ctx.msg(
                        admin,
                        "admin.settings.pause_options_set",
                        mapOf("option" to opt.name, "state" to if (enabled) "ENABLED" else "DISABLED")
                    )
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
        ctx.plugin.adminActions.updateSettingsConfig(admin, "public_enabled", next)

        if (next) {
            ctx.msg(admin, "admin.system.public_enabled")
        } else {
            ctx.msg(admin, "admin.system.public_disabled")
        }
    }
}

