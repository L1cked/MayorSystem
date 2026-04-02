package mayorSystem.elections

import mayorSystem.cloud.CommandContext
import mayorSystem.elections.ui.AdminElectionMenu
import mayorSystem.elections.ui.AdminFakeVotesMenu
import mayorSystem.elections.ui.AdminElectionSettingsMenu
import mayorSystem.elections.ui.AdminForceElectFlow
import mayorSystem.elections.ui.AdminForceElectMenu
import mayorSystem.elections.ui.AdminForceElectSectionsMenu
import mayorSystem.elections.ui.AdminSettingsTermMenu
import mayorSystem.security.Perms
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.IntegerParser.integerParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider
import java.time.Duration
import java.time.OffsetDateTime
import kotlinx.coroutines.launch

class ElectionsCommands(private val ctx: CommandContext) {
    private val onlinePlayerSuggestions = SuggestionProvider.blockingStrings<org.incendo.cloud.paper.util.sender.Source> { _, _ ->
        Bukkit.getOnlinePlayers().map { it.name }.sortedBy { it.lowercase() }
    }
    private val electionTimingSuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        listOf("while_term", "after_term")
    )

    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm

        ctx.registerMenuRoute(
            literals = listOf("admin", "election"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_ELECTION_START),
                Permission.of(Perms.ADMIN_ELECTION_END),
                Permission.of(Perms.ADMIN_ELECTION_CLEAR),
                Permission.of(Perms.ADMIN_ELECTION_ELECT),
                Permission.of(Perms.ADMIN_ELECTION_FAKE_VOTES)
            ),
            menuFactory = { AdminElectionMenu(plugin) }
        )

        // Election start/end/clear
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("election")
                .literal("start")
                .permission(Perms.ADMIN_ELECTION_START)
                .handler { command ->
                    val sender = command.sender().source()
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(sender, plugin.adminActions.forceStartElectionNow(sender as? Player))
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("election")
                .literal("end")
                .permission(Perms.ADMIN_ELECTION_END)
                .handler { command ->
                    val sender = command.sender().source()
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(sender, plugin.adminActions.forceEndElectionNow(sender as? Player))
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("election")
                .literal("clear")
                .permission(Perms.ADMIN_ELECTION_CLEAR)
                .handler { command ->
                    val sender = command.sender().source()
                    val term = plugin.termService.computeNow().second
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(sender, plugin.adminActions.clearAllOverridesForTerm(sender as? Player, term))
                    }
                }
        )

        // Elect menu routes
        ctx.registerMenuRoute(
            literals = listOf("admin", "election", "elect"),
            permission = Permission.of(Perms.ADMIN_ELECTION_ELECT),
            menuFactory = { AdminForceElectMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "election", "elect", "set"),
            permission = Permission.of(Perms.ADMIN_ELECTION_ELECT),
            menuFactory = { AdminForceElectMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "election", "elect", "now"),
            permission = Permission.of(Perms.ADMIN_ELECTION_ELECT),
            menuFactory = { AdminForceElectMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "election", "fakevotes"),
            permission = Permission.of(Perms.ADMIN_ELECTION_FAKE_VOTES),
            menuFactory = { AdminFakeVotesMenu(plugin, plugin.termService.computeNow().second) }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("election")
                .literal("elect")
                .literal("set")
                .permission(Perms.ADMIN_ELECTION_ELECT)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val name = command.get<String>("player")
                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    val uuid = off.uniqueId
                    val resolvedName = off.name ?: name
                    val electionTerm = plugin.termService.computeNow().second
                    val availableIds = plugin.perks.availablePerksForCandidate(electionTerm, uuid)
                        .map { it.id }
                        .toSet()
                    val preselected = if (plugin.store.isCandidate(electionTerm, uuid)) {
                        plugin.store.chosenPerks(electionTerm, uuid).filter { it in availableIds }.toSet()
                    } else {
                        emptySet()
                    }
                    AdminForceElectFlow.start(
                        admin.uniqueId,
                        electionTerm,
                        uuid,
                        resolvedName,
                        preselected,
                        AdminForceElectFlow.Mode.SET_FORCED
                    )
                    plugin.gui.open(admin, AdminForceElectSectionsMenu(plugin))
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("election")
                .literal("elect")
                .literal("clear")
                .permission(Perms.ADMIN_ELECTION_ELECT)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin = command.sender().source()
                    val electionTerm = plugin.termService.computeNow().second
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(admin, plugin.adminActions.clearForcedMayor(admin, electionTerm))
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("election")
                .literal("elect")
                .literal("now")
                .permission(Perms.ADMIN_ELECTION_ELECT)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val name = command.get<String>("player")

                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    val uuid = off.uniqueId
                    val resolvedName = off.name ?: name

                    val electionTerm = plugin.termService.computeNow().second
                    val availableIds = plugin.perks.availablePerksForCandidate(electionTerm, uuid)
                        .map { it.id }
                        .toSet()
                    val preselected = if (plugin.store.isCandidate(electionTerm, uuid)) {
                        plugin.store.chosenPerks(electionTerm, uuid).filter { it in availableIds }.toSet()
                    } else {
                        emptySet()
                    }

                    AdminForceElectFlow.start(
                        admin.uniqueId,
                        electionTerm,
                        uuid,
                        resolvedName,
                        preselected,
                        AdminForceElectFlow.Mode.ELECT_NOW
                    )
                    plugin.gui.open(admin, AdminForceElectSectionsMenu(plugin))
                }
        )

        // Settings menu routes
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "term_length"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "vote_window"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "first_term_start"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "perks_per_term"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "election_timing"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "allow_vote_change"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminElectionSettingsMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "stepdown_reapply"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminElectionSettingsMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "election"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminElectionSettingsMenu(plugin) }
        )

        // Settings commands
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("term_length")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val raw = command.get<String>("value")
                    val duration = runCatching { Duration.parse(raw) }.getOrNull()
                    if (duration == null) {
                        ctx.msg(admin, "admin.settings.duration_invalid", mapOf("example" to "P14D"))
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "term.length",
                                duration.toString(),
                                "admin.settings.term_length_set",
                                mapOf("value" to duration.toString())
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("vote_window")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val raw = command.get<String>("value")
                    val duration = runCatching { Duration.parse(raw) }.getOrNull()
                    if (duration == null) {
                        ctx.msg(admin, "admin.settings.duration_invalid", mapOf("example" to "P3D"))
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "term.vote_window",
                                duration.toString(),
                                "admin.settings.vote_window_set",
                                mapOf("value" to duration.toString())
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("first_term_start")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val raw = command.get<String>("value")
                    val dt = runCatching { OffsetDateTime.parse(raw) }.getOrNull()
                    if (dt == null) {
                        ctx.msg(admin, "admin.settings.datetime_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "term.first_term_start",
                                dt.toString(),
                                "admin.settings.first_term_start_set",
                                mapOf("value" to dt.toString())
                            )
                        )
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("perks_per_term")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", integerParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val value = command.get<Int>("value").coerceAtLeast(0)
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "term.perks_per_term",
                                value,
                                "admin.settings.perks_per_term_set",
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
                .literal("election_timing")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser(), electionTimingSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val raw = command.get<String>("value").trim().lowercase()
                    val after = when (raw) {
                        "after_term", "after_term_end", "after_term_ends", "after" -> true
                        "while_term", "during_term", "while", "before_term" -> false
                        else -> null
                    }
                    if (after == null) {
                        ctx.msg(admin, "admin.settings.election_timing_invalid")
                        return@handler
                    }
                    val value = if (after) "AFTER_TERM" else "WHILE_TERM"
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "term.election_after_term_end",
                                after,
                                "admin.settings.election_timing_set",
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
                .literal("allow_vote_change")
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
                                "election.allow_vote_change",
                                value,
                                "admin.settings.allow_vote_change_set",
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
                .literal("stepdown_reapply")
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
                                "election.stepdown.allow_reapply",
                                value,
                                "admin.settings.stepdown_reapply_set",
                                mapOf("value" to value.toString())
                            )
                        )
                    }
                }
        )
    }
}

