package mayorSystem.candidates

import mayorSystem.cloud.CommandContext
import mayorSystem.candidates.ui.AdminApplyBanSearchMenu
import mayorSystem.candidates.ui.AdminCandidatesMenu
import mayorSystem.candidates.ui.AdminSettingsApplyMenu
import mayorSystem.data.CandidateStatus
import mayorSystem.security.Perms
import mayorSystem.util.ProfileResolver
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.IntegerParser.integerParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CandidatesCommands(private val ctx: CommandContext) {
    private val onlinePlayerSuggestions = SuggestionProvider.blockingStrings<org.incendo.cloud.paper.util.sender.Source> { _, _ ->
        Bukkit.getOnlinePlayers().map { it.name }.sortedBy { it.lowercase() }
    }

    private fun resolveProfile(
        admin: Player,
        name: String,
        callback: (uuid: java.util.UUID, resolvedName: String) -> Unit
    ) {
        ProfileResolver.resolve(
            ctx.plugin,
            name,
            onError = { ctx.msg(admin, "admin.candidate.profile_lookup_failed", mapOf("name" to name)) },
            callback = callback
        )
    }

    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm

        ctx.registerMenuRoute(
            literals = listOf("admin", "candidates"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_CANDIDATES_REMOVE),
                Permission.of(Perms.ADMIN_CANDIDATES_RESTORE),
                Permission.of(Perms.ADMIN_CANDIDATES_PROCESS),
                Permission.of(Perms.ADMIN_CANDIDATES_APPLYBAN)
            ),
            menuFactory = { AdminCandidatesMenu(plugin) }
        )

        ctx.registerMenuRoute(
            literals = listOf("admin", "candidates", "remove"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_REMOVE),
            menuFactory = { AdminCandidatesMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "candidates", "restore"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_RESTORE),
            menuFactory = { AdminCandidatesMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "candidates", "process"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_PROCESS),
            menuFactory = { AdminCandidatesMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "candidates", "applyban"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_APPLYBAN),
            menuFactory = { AdminApplyBanSearchMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "candidates", "applyban", "perm"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_APPLYBAN),
            menuFactory = { AdminApplyBanSearchMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "candidates", "applyban", "temp"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_APPLYBAN),
            menuFactory = { AdminApplyBanSearchMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "candidates", "applyban", "clear"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_APPLYBAN),
            menuFactory = { AdminApplyBanSearchMenu(plugin) }
        )

        // Candidate status commands
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("candidates")
                .literal("remove")
                .permission(Perms.ADMIN_CANDIDATES_REMOVE)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val name = command.get<String>("player")
                    adminSetCandidateStatus(admin, name, CandidateStatus.REMOVED)
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("candidates")
                .literal("restore")
                .permission(Perms.ADMIN_CANDIDATES_RESTORE)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val name = command.get<String>("player")
                    adminSetCandidateStatus(admin, name, CandidateStatus.ACTIVE)
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("candidates")
                .literal("process")
                .permission(Perms.ADMIN_CANDIDATES_PROCESS)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val name = command.get<String>("player")
                    adminSetCandidateStatus(admin, name, CandidateStatus.PROCESS)
                }
        )

        // Apply bans
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("candidates")
                .literal("applyban")
                .literal("perm")
                .permission(Perms.ADMIN_CANDIDATES_APPLYBAN)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val name = command.get<String>("player")
                    resolveProfile(admin, name) { uuid, resolvedName ->
                        plugin.scope.launch(plugin.mainDispatcher) {
                            ctx.dispatch(admin, plugin.adminActions.setApplyBanPermanent(admin, uuid, resolvedName))
                        }
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("candidates")
                .literal("applyban")
                .literal("temp")
                .permission(Perms.ADMIN_CANDIDATES_APPLYBAN)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .required("days", integerParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val name = command.get<String>("player")
                    val rawDays = command.get<Int>("days")
                    if (rawDays < 1) {
                        ctx.msg(admin, "admin.settings.value_min_adjusted", mapOf("value" to rawDays.toString(), "adjusted" to "1"))
                    }
                    val days = rawDays.coerceAtLeast(1)
                    val until = OffsetDateTime.now().plusDays(days.toLong())
                    resolveProfile(admin, name) { uuid, resolvedName ->
                        plugin.scope.launch(plugin.mainDispatcher) {
                            ctx.dispatch(admin, plugin.adminActions.setApplyBanTemp(admin, uuid, resolvedName, until))
                        }
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("candidates")
                .literal("applyban")
                .literal("clear")
                .permission(Perms.ADMIN_CANDIDATES_APPLYBAN)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val name = command.get<String>("player")
                    resolveProfile(admin, name) { uuid, resolvedName ->
                        plugin.scope.launch(plugin.mainDispatcher) {
                            ctx.dispatch(admin, plugin.adminActions.clearApplyBan(admin, uuid, resolvedName))
                        }
                    }
                }
        )

        // Settings menu routes
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "apply_cost"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsApplyMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "playtime_minutes"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsApplyMenu(plugin) }
        )

        // Settings commands
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("playtime_minutes")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", integerParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val rawValue = command.get<Int>("value")
                    if (rawValue < 0) {
                        ctx.msg(admin, "admin.settings.value_min_adjusted", mapOf("value" to rawValue.toString(), "adjusted" to "0"))
                    }
                    val value = rawValue.coerceAtLeast(0)
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "apply.playtime_minutes",
                                value,
                                "admin.settings.playtime_set",
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
                .literal("apply_cost")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val raw = command.get<String>("value")
                    val value = raw.toDoubleOrNull()
                    if (value == null) {
                        ctx.msg(admin, "admin.settings.apply_cost_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        ctx.dispatch(
                            admin,
                            plugin.adminActions.updateSettingsConfig(
                                admin,
                                "apply.cost",
                                value,
                                "admin.settings.apply_cost_set",
                                mapOf("value" to BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())
                            )
                        )
                    }
                }
        )
    }

    private fun adminSetCandidateStatus(admin: Player, name: String, status: CandidateStatus) {
        val now = Instant.now()
        val term = ctx.plugin.termService.computeCached(now).second
        ctx.plugin.scope.launch(ctx.plugin.mainDispatcher) {
            val candidates = withContext(Dispatchers.IO) {
                ctx.plugin.store.candidates(term, includeRemoved = true)
            }
            val entry = candidates.firstOrNull { it.lastKnownName.equals(name, ignoreCase = true) }
            if (entry == null) {
                ctx.msg(admin, "admin.candidate.not_found", mapOf("name" to name))
                val names = candidates.map { it.lastKnownName }
                if (names.isNotEmpty()) {
                    ctx.msg(admin, "admin.candidate.list", mapOf("names" to names.joinToString(", ")))
                }
                return@launch
            }
            ctx.dispatch(
                admin,
                ctx.plugin.adminActions.setCandidateStatus(admin, term, entry.uuid, status, entry.lastKnownName)
            )
        }
    }
}

