package mayorSystem.governance

import mayorSystem.cloud.CommandContext
import mayorSystem.config.MayorStepdownPolicy
import mayorSystem.config.TiePolicy
import mayorSystem.governance.ui.GovernanceSettingsMenu
import mayorSystem.security.Perms
import org.bukkit.entity.Player
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.IntegerParser.integerParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider

class GovernanceCommands(private val ctx: CommandContext) {
    private val tiePolicySuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        TiePolicy.values().map { it.name }
    )
    private val mayorStepdownSuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        MayorStepdownPolicy.values().map { it.name }
    )

    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm
        val governancePerm = Permission.anyOf(
            Permission.of(Perms.ADMIN_GOVERNANCE_EDIT),
            Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            Permission.of(Perms.LEGACY_ADMIN_SETTINGS),
            Permission.of(Perms.LEGACY_ADMIN_UMBRELLA)
        )

        ctx.registerMenuRoute(
            literals = listOf("admin", "governance"),
            permission = governancePerm,
            menuFactory = { GovernanceSettingsMenu(plugin) }
        )

        // Legacy settings menu route
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "term_extras"),
            permission = governancePerm,
            menuFactory = { GovernanceSettingsMenu(plugin) }
        )

        // Bonus term settings
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("bonus_enabled")
                .permission(governancePerm)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val value = ctx.parseBool(command.get("value")) ?: run {
                        ctx.msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "term.bonus_term.enabled", value)
                    ctx.msg(admin, "admin.settings.bonus_enabled_set", mapOf("value" to value.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("bonus_every")
                .permission(governancePerm)
                .senderType(PlayerSource::class.java)
                .required("value", integerParser())
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val value = command.get<Int>("value").coerceAtLeast(1)
                    plugin.adminActions.updateSettingsConfig(admin, "term.bonus_term.every_x_terms", value)
                    ctx.msg(admin, "admin.settings.bonus_every_set", mapOf("value" to value.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("bonus_perks")
                .permission(governancePerm)
                .senderType(PlayerSource::class.java)
                .required("value", integerParser())
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val value = command.get<Int>("value").coerceAtLeast(1)
                    plugin.adminActions.updateSettingsConfig(admin, "term.bonus_term.perks_per_bonus_term", value)
                    ctx.msg(admin, "admin.settings.bonus_perks_set", mapOf("value" to value.toString()))
                }
        )

        // Tie policy
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("tie_policy")
                .permission(governancePerm)
                .senderType(PlayerSource::class.java)
                .required("policy", stringParser(), tiePolicySuggestions)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val raw = command.get<String>("policy")
                    val policy = runCatching { TiePolicy.valueOf(raw.uppercase()) }.getOrNull()
                    if (policy == null) {
                        ctx.msg(admin, "admin.settings.tie_policy_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "election.tie_policy", policy.name)
                    ctx.msg(admin, "admin.settings.tie_policy_set", mapOf("value" to policy.name))
                }
        )

        // Mayor stepdown policy
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("mayor_stepdown")
                .permission(governancePerm)
                .senderType(PlayerSource::class.java)
                .required("policy", stringParser(), mayorStepdownSuggestions)
                .handler { command ->
                    val admin: Player = command.sender().source()
                    val raw = command.get<String>("policy")
                    val policy = runCatching { MayorStepdownPolicy.valueOf(raw.uppercase()) }.getOrNull()
                    if (policy == null) {
                        ctx.msg(admin, "admin.settings.mayor_stepdown_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "election.mayor_stepdown", policy.name)
                    ctx.msg(admin, "admin.settings.mayor_stepdown_set", mapOf("value" to policy.name))
                }
        )
    }
}

