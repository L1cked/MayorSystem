package mayorSystem.elections

import mayorSystem.cloud.CommandContext
import mayorSystem.elections.ui.AdminElectionMenu
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

    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm

        ctx.registerMenuRoute(
            literals = listOf("admin", "election"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_ELECTION_START),
                Permission.of(Perms.ADMIN_ELECTION_END),
                Permission.of(Perms.ADMIN_ELECTION_CLEAR),
                Permission.of(Perms.ADMIN_ELECTION_ELECT)
            ),
            menuFactory = { AdminElectionMenu(plugin) }
        )

        // Election start/end/clear
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("election")
                .literal("start")
                .permission(Perms.ADMIN_ELECTION_START)
                .handler { command ->
                    val sender = command.sender().source()
                    plugin.scope.launch(plugin.mainDispatcher) {
                        val ok = plugin.adminActions.forceStartElectionNow(sender as? Player)
                        if (ok) ctx.msg(sender, "admin.election.started") else ctx.msg(sender, "admin.election.start_failed")
                    }
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("election")
                .literal("end")
                .permission(Perms.ADMIN_ELECTION_END)
                .handler { command ->
                    val sender = command.sender().source()
                    plugin.scope.launch(plugin.mainDispatcher) {
                        val ok = plugin.adminActions.forceEndElectionNow(sender as? Player)
                        if (ok) ctx.msg(sender, "admin.election.ended") else ctx.msg(sender, "admin.election.end_failed")
                    }
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("election")
                .literal("clear")
                .permission(Perms.ADMIN_ELECTION_CLEAR)
                .handler { command ->
                    val sender = command.sender().source()
                    val term = plugin.termService.computeNow().second
                    plugin.scope.launch(plugin.mainDispatcher) {
                        plugin.adminActions.clearAllOverridesForTerm(sender as? Player, term)
                        ctx.msg(sender, "admin.election.overrides_cleared", mapOf("term" to (term + 1).toString()))
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

        cm.command(
            cm.commandBuilder("mayor")
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
                    plugin.adminActions.setForcedMayor(admin, electionTerm, uuid, resolvedName)
                    ctx.msg(admin, "admin.election.forced_mayor_set", mapOf("term" to (electionTerm + 1).toString(), "name" to resolvedName))
                    ctx.msg(admin, "admin.election.forced_mayor_hint")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("election")
                .literal("elect")
                .literal("clear")
                .permission(Perms.ADMIN_ELECTION_ELECT)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val admin = command.sender().source()
                    val electionTerm = plugin.termService.computeNow().second
                    plugin.adminActions.clearForcedMayor(admin, electionTerm)
                    ctx.msg(admin, "admin.election.forced_mayor_cleared", mapOf("term" to (electionTerm + 1).toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
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

                    AdminForceElectFlow.start(admin.uniqueId, electionTerm, uuid, resolvedName, preselected)
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
            cm.commandBuilder("mayor")
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
                    plugin.adminActions.updateSettingsConfig(admin, "term.length", duration.toString())
                    ctx.msg(admin, "admin.settings.term_length_set", mapOf("value" to duration.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
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
                    plugin.adminActions.updateSettingsConfig(admin, "term.vote_window", duration.toString())
                    ctx.msg(admin, "admin.settings.vote_window_set", mapOf("value" to duration.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
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
                    plugin.adminActions.updateSettingsConfig(admin, "term.first_term_start", dt.toString())
                    ctx.msg(admin, "admin.settings.first_term_start_set", mapOf("value" to dt.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("perks_per_term")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", integerParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val value = command.get<Int>("value").coerceAtLeast(0)
                    plugin.adminActions.updateSettingsConfig(admin, "term.perks_per_term", value)
                    ctx.msg(admin, "admin.settings.perks_per_term_set", mapOf("value" to value.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
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
                    plugin.adminActions.updateSettingsConfig(admin, "election.allow_vote_change", value)
                    ctx.msg(admin, "admin.settings.allow_vote_change_set", mapOf("value" to value.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
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
                    plugin.adminActions.updateSettingsConfig(admin, "election.stepdown.allow_reapply", value)
                    ctx.msg(admin, "admin.settings.stepdown_reapply_set", mapOf("value" to value.toString()))
                }
        )
    }
}

