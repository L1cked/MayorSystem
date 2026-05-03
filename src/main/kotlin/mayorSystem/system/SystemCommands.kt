package mayorSystem.system

import mayorSystem.cloud.CommandContext
import mayorSystem.config.SystemGateOption
import mayorSystem.monitoring.HealthSeverity
import mayorSystem.monitoring.ui.AdminHealthMenu
import mayorSystem.rewards.DisplayRewardMode
import mayorSystem.rewards.DisplayRewardTargetType
import mayorSystem.rewards.DisplayRewardTagId
import mayorSystem.rewards.DeluxeTagsIntegration
import mayorSystem.rewards.TagIconSettings
import mayorSystem.security.Perms
import mayorSystem.showcase.ShowcaseMode
import mayorSystem.showcase.ShowcaseTarget
import mayorSystem.system.ui.AdminDisplayRewardTagIconMenu
import mayorSystem.system.ui.AdminDisplayRewardTargetsMenu
import mayorSystem.system.ui.AdminMenu
import mayorSystem.system.ui.AdminSettingsEnableOptionsMenu
import mayorSystem.system.ui.AdminSettingsGeneralMenu
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.system.ui.AdminSettingsPauseOptionsMenu
import mayorSystem.system.ui.AdminDisplayMenu
import mayorSystem.system.ui.AdminSettingsMayorGroupMenu
import mayorSystem.system.ui.DisplayRewardTargetKind
import net.luckperms.api.LuckPermsProvider
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.paper.util.sender.Source
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.parser.standard.StringParser.greedyStringParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider
import kotlinx.coroutines.launch

class SystemCommands(private val ctx: CommandContext) {
    private val gateOptionSuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        SystemGateOption.values().map { it.name }
    )
    private val showcaseModeSuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        listOf("switching", "individual")
    )
    private val groupNameRegex = Regex("^[A-Za-z0-9_.-]+$")
    private val rewardModeSuggestions = SuggestionProvider.suggestingStrings<Source>(
        DisplayRewardMode.entries.map { it.name }
    )
    private val booleanSuggestions = SuggestionProvider.suggestingStrings<Source>("true", "false")
    private val rewardTargetTypeSuggestions = SuggestionProvider.blockingStrings<Source> { context, _ ->
        if (!canSuggestReward(context.sender().source(), edit = true)) emptyList() else listOf("track", "group", "user")
    }
    private val rewardViewTargetTypeSuggestions = SuggestionProvider.blockingStrings<Source> { context, _ ->
        if (!canSuggestReward(context.sender().source(), edit = false)) emptyList() else listOf("track", "group", "user")
    }
    private val rewardMenuSuggestions = SuggestionProvider.blockingStrings<Source> { context, _ ->
        if (!canSuggestReward(context.sender().source(), edit = false)) {
            emptyList()
        } else {
            listOf("main", "tracks", "groups", "users", "tag", "tag_icon", "health")
        }
    }
    private val rewardTargetSuggestions = SuggestionProvider.blockingStrings<Source> { context, _ ->
        if (!canSuggestReward(context.sender().source(), edit = false)) return@blockingStrings emptyList()
        when (DisplayRewardTargetType.parse(runCatching { context.get<String>("type") }.getOrNull())) {
            DisplayRewardTargetType.TRACK -> luckPermsTrackNames() + ctx.plugin.settings.displayReward.targets.tracks.keys
            DisplayRewardTargetType.GROUP -> luckPermsGroupNames() + ctx.plugin.settings.displayReward.targets.groups.keys
            DisplayRewardTargetType.USER -> knownUserTargets()
            null -> emptyList()
        }.distinct().sortedBy { it.lowercase() }
    }
    private val tagIdSuggestions = SuggestionProvider.blockingStrings<Source> { context, _ ->
        if (!canSuggestReward(context.sender().source(), edit = true)) {
            emptyList()
        } else {
            (
                listOf(ctx.plugin.settings.displayReward.tag.deluxeTagId, "mayor_current") +
                    DeluxeTagsIntegration(ctx.plugin).loadedTagIds()
                ).distinct()
        }
    }
    private val itemMaterialSuggestions = SuggestionProvider.blockingStrings<Source> { context, _ ->
        if (!canSuggestReward(context.sender().source(), edit = true)) {
            emptyList()
        } else {
            Material.entries
                .asSequence()
                .filter(TagIconSettings::isUsableItemMaterial)
                .map { it.name }
                .toList()
        }
    }
    private val adminRootPermission: Permission = Permission.anyOf(
        *listOf(Permission.of(Perms.ADMIN_ACCESS))
            .plus(Perms.ADMIN_ACTION_PERMS.map(Permission::of))
            .toTypedArray()
    )

    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm

        // /mayor admin -> admin panel
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .permission(adminRootPermission)
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
            ctx.rootCommandBuilder()
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
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
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
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
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
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
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
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
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
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
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
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
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
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
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
                Permission.of(Perms.ADMIN_PERKS_CATALOG),
                Permission.of(Perms.ADMIN_REWARD_VIEW),
                Permission.of(Perms.ADMIN_REWARD_EDIT),
                Permission.of(Perms.ADMIN_NPC_MAYOR),
                Permission.of(Perms.ADMIN_HOLOGRAM_LEADERBOARD)
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
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_REWARD_VIEW),
                Permission.of(Perms.ADMIN_REWARD_EDIT),
                Permission.of(Perms.ADMIN_SETTINGS_EDIT)
            ),
            menuFactory = { AdminSettingsMayorGroupMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "display_reward"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_REWARD_VIEW),
                Permission.of(Perms.ADMIN_REWARD_EDIT),
                Permission.of(Perms.ADMIN_SETTINGS_EDIT)
            ),
            menuFactory = { AdminSettingsMayorGroupMenu(plugin) }
        )
        registerDisplayRewardCommands()
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
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender().source()
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
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender().source()
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
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_REWARD_EDIT),
                        Permission.of(Perms.ADMIN_SETTINGS_EDIT)
                    )
                )
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val value = ctx.parseBool(command.get("value")) ?: run {
                        ctx.msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        val path = if (plugin.settings.displayReward.configured) {
                            "display_reward.rank.enabled"
                        } else {
                            "title.username_group_enabled"
                        }
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateDisplayRewardConfig(
                                admin,
                                path,
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
                .permission(
                    Permission.anyOf(
                        Permission.of(Perms.ADMIN_REWARD_EDIT),
                        Permission.of(Perms.ADMIN_SETTINGS_EDIT)
                    )
                )
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val value = command.get<String>("value").trim()
                    if (value.isBlank() || !groupNameRegex.matches(value)) {
                        ctx.msg(admin, "admin.settings.mayor_group_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        val path = if (plugin.settings.displayReward.configured) {
                            "display_reward.rank.luckperms_group"
                        } else {
                            "title.username_group"
                        }
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateDisplayRewardConfig(
                                admin,
                                path,
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

    private fun registerDisplayRewardCommands() {
        val plugin = ctx.plugin
        val cm = ctx.cm
        val viewPerm = Permission.anyOf(
            Permission.of(Perms.ADMIN_REWARD_VIEW),
            Permission.of(Perms.ADMIN_REWARD_EDIT),
            Permission.of(Perms.ADMIN_SETTINGS_EDIT)
        )
        val editPerm = Permission.anyOf(
            Permission.of(Perms.ADMIN_REWARD_EDIT),
            Permission.of(Perms.ADMIN_SETTINGS_EDIT)
        )

        ctx.registerMenuRoute(listOf("admin", "reward"), viewPerm, { AdminSettingsMayorGroupMenu(plugin) })
        ctx.registerMenuRoute(listOf("admin", "reward", "tracks"), viewPerm, { AdminDisplayRewardTargetsMenu(plugin, DisplayRewardTargetKind.TRACKS) })
        ctx.registerMenuRoute(listOf("admin", "reward", "groups"), viewPerm, { AdminDisplayRewardTargetsMenu(plugin, DisplayRewardTargetKind.GROUPS) })
        ctx.registerMenuRoute(listOf("admin", "reward", "users"), viewPerm, { AdminDisplayRewardTargetsMenu(plugin, DisplayRewardTargetKind.USERS) })
        ctx.registerMenuRoute(listOf("admin", "reward", "tag_icon"), viewPerm, { AdminDisplayRewardTagIconMenu(plugin) })

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("open")
                .permission(viewPerm)
                .senderType(PlayerSource::class.java)
                .required("menu", stringParser(), rewardMenuSuggestions)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    openRewardMenu(admin, command.get("menu"))
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("health")
                .permission(viewPerm)
                .handler { command ->
                    val sender = command.sender().source()
                    val admin = sender as? Player
                    if (admin != null) {
                        plugin.gui.open(admin, AdminHealthMenu(plugin))
                    } else {
                        sendDisplayRewardHealth(sender)
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("sync")
                .permission(editPerm)
                .handler { command ->
                    val sender = command.sender().source()
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(sender, plugin.adminActions.syncDisplayReward(sender as? Player))
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("default")
                .permission(editPerm)
                .senderType(PlayerSource::class.java)
                .required("mode", stringParser(), rewardModeSuggestions)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val mode = DisplayRewardMode.parse(command.get<String>("mode"))
                    if (mode == null) {
                        ctx.msg(admin, "admin.settings.display_reward_mode_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(admin, plugin.adminActions.setDisplayRewardDefaultMode(admin, mode))
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("list")
                .permission(viewPerm)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    ctx.dispatch(admin, plugin.adminActions.listDisplayRewardTargets(admin, null))
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("list")
                .permission(viewPerm)
                .senderType(PlayerSource::class.java)
                .required("type", stringParser(), rewardViewTargetTypeSuggestions)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val type = DisplayRewardTargetType.parse(command.get<String>("type"))
                    if (type == null) {
                        ctx.msg(admin, "admin.settings.display_reward_target_invalid")
                        return@handler
                    }
                    ctx.dispatch(admin, plugin.adminActions.listDisplayRewardTargets(admin, type))
                }
        )

        fun targetCommand(action: String, withMode: Boolean) {
            var builder = ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal(action)
                .permission(if (action == "inspect") viewPerm else editPerm)
                .senderType(PlayerSource::class.java)
                .required("type", stringParser(), if (action == "inspect") rewardViewTargetTypeSuggestions else rewardTargetTypeSuggestions)
                .required("target", stringParser(), rewardTargetSuggestions)
            if (withMode) {
                builder = builder.required("mode", stringParser(), rewardModeSuggestions)
            }
            cm.command(
                builder.handler { command ->
                    val admin: Player = command.sender().source()
                    val type = DisplayRewardTargetType.parse(command.get<String>("type"))
                    if (type == null) {
                        ctx.msg(admin, "admin.settings.display_reward_target_invalid")
                        return@handler
                    }
                    val target = command.get<String>("target")
                    when (action) {
                        "inspect" -> ctx.dispatch(admin, plugin.adminActions.inspectDisplayRewardTarget(admin, type, target))
                        "remove" -> plugin.scope.launch(plugin.mainDispatcher) {
                            ctx.dispatch(admin, plugin.adminActions.removeDisplayRewardTarget(admin, type, target))
                        }
                        "add", "edit" -> {
                            val mode = DisplayRewardMode.parse(command.get<String>("mode"))
                            if (mode == null) {
                                ctx.msg(admin, "admin.settings.display_reward_mode_invalid")
                                return@handler
                            }
                            plugin.scope.launch(plugin.mainDispatcher) {
                                ctx.dispatch(admin, plugin.adminActions.setDisplayRewardTarget(admin, type, target, mode))
                            }
                        }
                    }
                }
            )
        }
        targetCommand("inspect", withMode = false)
        targetCommand("remove", withMode = false)
        targetCommand("add", withMode = true)
        targetCommand("edit", withMode = true)

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("rank")
                .literal("group")
                .permission(editPerm)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser(), SuggestionProvider.blockingStrings<Source> { context, _ ->
                    if (!canSuggestReward(context.sender().source(), edit = true)) emptyList() else luckPermsGroupNames()
                })
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val value = command.get<String>("value").trim()
                    if (value.isBlank() || !groupNameRegex.matches(value)) {
                        ctx.msg(admin, "admin.settings.mayor_group_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateDisplayRewardConfig(
                                admin,
                                "display_reward.rank.luckperms_group",
                                value,
                                "admin.settings.display_reward_rank_group_set",
                                mapOf("value" to value)
                            )
                        )
                    }
                }
        )

        registerTagRewardCommands(editPerm)
    }

    private fun registerTagRewardCommands(editPerm: Permission) {
        val plugin = ctx.plugin
        val cm = ctx.cm

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("tag")
                .literal("id")
                .permission(editPerm)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser(), tagIdSuggestions)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val value = command.get<String>("value").trim()
                    if (!DisplayRewardTagId.isValid(value)) {
                        ctx.msg(admin, "admin.settings.display_reward_tag_id_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateDisplayRewardConfig(
                                admin,
                                "display_reward.tag.deluxe_tag_id",
                                value,
                                "admin.settings.display_reward_tag_id_set",
                                mapOf("value" to value)
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("tag")
                .literal("display")
                .permission(editPerm)
                .senderType(PlayerSource::class.java)
                .required("value", greedyStringParser())
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val value = command.get<String>("value").trim().take(64)
                    if (value.isBlank()) {
                        ctx.msg(admin, "admin.settings.display_reward_value_required")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateDisplayRewardConfig(
                                admin,
                                "display_reward.tag.display",
                                value,
                                "admin.settings.display_reward_tag_display_set",
                                mapOf("value" to value)
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("tag")
                .literal("description")
                .permission(editPerm)
                .senderType(PlayerSource::class.java)
                .required("value", greedyStringParser())
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val value = command.get<String>("value").trim().take(96)
                    if (value.isBlank()) {
                        ctx.msg(admin, "admin.settings.display_reward_value_required")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateDisplayRewardConfig(
                                admin,
                                "display_reward.tag.description",
                                value,
                                "admin.settings.display_reward_tag_description_set",
                                mapOf("value" to value)
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("tag")
                .literal("before_rank")
                .permission(editPerm)
                .required("value", stringParser(), booleanSuggestions)
                .handler { command ->
                    val sender = command.sender().source()
                    val admin = sender as? Player
                    val value = ctx.parseBool(command.get("value")) ?: run {
                        ctx.msg(sender, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            sender,
                            plugin.adminActions.updateDisplayRewardConfig(
                                admin,
                                "display_reward.tag.render_before_luckperms",
                                value,
                                "admin.settings.display_reward_tag_before_rank_set",
                                mapOf("value" to value.toString())
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("tag")
                .literal("icon")
                .permission(editPerm)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val icon = plugin.settings.displayReward.tag.icon
                    ctx.msg(
                        admin,
                        "admin.settings.display_reward_icon_view",
                        mapOf(
                            "material" to icon.materialOrDefault().name,
                            "custom_model_data" to (icon.customModelData?.toString() ?: "none"),
                            "glint" to icon.glint.toString()
                        )
                    )
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("tag")
                .literal("icon")
                .literal("material")
                .permission(editPerm)
                .senderType(PlayerSource::class.java)
                .required("material", stringParser(), itemMaterialSuggestions)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.setDisplayRewardTagIconMaterial(admin, command.get("material"))
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("tag")
                .literal("icon")
                .literal("reset")
                .permission(editPerm)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(admin, plugin.adminActions.resetDisplayRewardTagIcon(admin))
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("tag")
                .literal("icon")
                .literal("custom_model_data")
                .permission(editPerm)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser(), SuggestionProvider.suggestingStrings<Source>("clear", "reset", "0"))
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val raw = command.get<String>("value").trim()
                    val value = when (raw.lowercase()) {
                        "clear", "reset" -> null
                        else -> raw.toIntOrNull()?.takeIf { it >= 0 } ?: run {
                            ctx.msg(admin, "admin.settings.display_reward_custom_model_invalid")
                            return@handler
                        }
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(admin, plugin.adminActions.setDisplayRewardTagIconCustomModelData(admin, value))
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("reward")
                .literal("tag")
                .literal("icon")
                .literal("glint")
                .permission(editPerm)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(admin, plugin.adminActions.toggleDisplayRewardTagIconGlint(admin))
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

    private fun openRewardMenu(admin: Player, raw: String) {
        when (raw.trim().lowercase()) {
            "main", "display", "reward" -> ctx.plugin.gui.open(admin, AdminSettingsMayorGroupMenu(ctx.plugin))
            "track", "tracks" -> ctx.plugin.gui.open(admin, AdminDisplayRewardTargetsMenu(ctx.plugin, DisplayRewardTargetKind.TRACKS))
            "group", "groups" -> ctx.plugin.gui.open(admin, AdminDisplayRewardTargetsMenu(ctx.plugin, DisplayRewardTargetKind.GROUPS))
            "user", "users" -> ctx.plugin.gui.open(admin, AdminDisplayRewardTargetsMenu(ctx.plugin, DisplayRewardTargetKind.USERS))
            "tag", "tag_settings" -> ctx.plugin.gui.open(admin, AdminSettingsMayorGroupMenu(ctx.plugin))
            "icon", "tag_icon" -> ctx.plugin.gui.open(admin, AdminDisplayRewardTagIconMenu(ctx.plugin))
            "health", "status" -> ctx.plugin.gui.open(admin, AdminHealthMenu(ctx.plugin))
            else -> ctx.msg(admin, "admin.settings.display_reward_menu_invalid")
        }
    }

    private fun sendDisplayRewardHealth(sender: CommandSender) {
        val checks = ctx.plugin.health.run()
            .filter {
                it.id.startsWith("display_reward") ||
                    it.id.startsWith("deluxetags") ||
                    it.id.startsWith("luckperms")
            }
        val errors = checks.count { it.severity == HealthSeverity.ERROR }
        val warnings = checks.count { it.severity == HealthSeverity.WARN }
        sender.sendMessage("Display Reward health: $errors error(s), $warnings warning(s).")
        val attention = checks.filter { it.severity != HealthSeverity.OK }
        if (attention.isEmpty()) {
            sender.sendMessage("Display Reward health looks good.")
            return
        }
        attention.take(12).forEach { check ->
            val details = check.details.take(2).joinToString("; ")
            val suffix = if (details.isBlank()) "" else " - $details"
            sender.sendMessage("${check.severity}: ${check.title}$suffix")
            check.suggestion?.let { sender.sendMessage("Action: $it") }
        }
        if (attention.size > 12) {
            sender.sendMessage("${attention.size - 12} more item(s) need attention.")
        }
    }

    private fun canSuggestReward(source: CommandSender, edit: Boolean): Boolean {
        val player = source as? Player ?: return true
        val settings = ctx.plugin.settings.displayReward
        if (edit) {
            return player.hasPermission(settings.adminEditPermission) || player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
        }
        return player.hasPermission(settings.adminViewPermission) ||
            player.hasPermission(settings.adminEditPermission) ||
            player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
    }

    private fun luckPermsGroupNames(): List<String> {
        val lpPlugin = ctx.plugin.server.pluginManager.getPlugin("LuckPerms")?.takeIf { it.isEnabled } ?: return emptyList()
        val lp = runCatching { LuckPermsProvider.get() }.getOrNull() ?: return emptyList()
        return lp.groupManager.loadedGroups.map { it.name }
    }

    private fun luckPermsTrackNames(): List<String> {
        ctx.plugin.server.pluginManager.getPlugin("LuckPerms")?.takeIf { it.isEnabled } ?: return emptyList()
        val lp = runCatching { LuckPermsProvider.get() }.getOrNull() ?: return emptyList()
        return lp.trackManager.loadedTracks.map { it.name }
    }

    private fun knownUserTargets(): List<String> =
        (
            Bukkit.getOnlinePlayers().map { it.name } +
                runCatching { ctx.plugin.offlinePlayers.snapshot(forceRefresh = false).entries.map { it.name } }.getOrDefault(emptyList()) +
                ctx.plugin.settings.displayReward.targets.users.keys.map { key ->
                    runCatching {
                        ctx.plugin.playerDisplayNames.resolve(java.util.UUID.fromString(key)).plain
                    }.getOrDefault("Unknown player")
                }
            )
            .filter { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            .distinct()

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

