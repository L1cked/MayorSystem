package mayorSystem.candidates

import mayorSystem.cloud.CommandContext
import mayorSystem.candidates.ui.AdminApplyBanSearchMenu
import mayorSystem.candidates.ui.AdminCandidatesMenu
import mayorSystem.candidates.ui.AdminSettingsApplyMenu
import mayorSystem.data.CandidateStatus
import mayorSystem.security.Perms
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.IntegerParser.integerParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider
import java.time.OffsetDateTime
import java.time.Instant
import kotlinx.coroutines.launch

class CandidatesCommands(private val ctx: CommandContext) {
    private val onlinePlayerSuggestions = SuggestionProvider.blockingStrings<org.incendo.cloud.paper.util.sender.Source> { _, _ ->
        Bukkit.getOnlinePlayers().map { it.name }.sortedBy { it.lowercase() }
    }

    private fun resolveProfile(name: String, callback: (uuid: java.util.UUID, resolvedName: String) -> Unit) {
        val plugin = ctx.plugin
        val cached = plugin.server.getOfflinePlayerIfCached(name)
        if (cached != null) {
            callback(cached.uniqueId, cached.name ?: name)
            return
        }
        val profile = Bukkit.createProfile(name)
        profile.update()
            .thenAccept { updated ->
                val uuid = updated.id ?: return@thenAccept
                val resolvedName = updated.name ?: name
                plugin.server.scheduler.runTask(plugin, Runnable { callback(uuid, resolvedName) })
            }
            .exceptionally {
                // Fall back to the original name if lookup fails.
                val off = plugin.server.getOfflinePlayerIfCached(name)
                if (off != null) {
                    plugin.server.scheduler.runTask(plugin, Runnable { callback(off.uniqueId, off.name ?: name) })
                }
                null
            }
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
                    resolveProfile(name) { uuid, resolvedName ->
                        plugin.scope.launch(plugin.mainDispatcher) {
                            plugin.adminActions.setApplyBanPermanent(admin, uuid, resolvedName)
                            ctx.msg(admin, "admin.applyban.permanent", mapOf("name" to resolvedName))
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
                    val days = command.get<Int>("days").coerceAtLeast(1)
                    val until = OffsetDateTime.now().plusDays(days.toLong())
                    resolveProfile(name) { uuid, resolvedName ->
                        plugin.scope.launch(plugin.mainDispatcher) {
                            plugin.adminActions.setApplyBanTemp(admin, uuid, resolvedName, until)
                            ctx.msg(admin, "admin.applyban.temp", mapOf("name" to resolvedName, "days" to days.toString()))
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
                    resolveProfile(name) { uuid, resolvedName ->
                        plugin.scope.launch(plugin.mainDispatcher) {
                            plugin.adminActions.clearApplyBan(admin, uuid)
                            ctx.msg(admin, "admin.applyban.cleared", mapOf("name" to resolvedName))
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
                    val value = command.get<Int>("value").coerceAtLeast(0)
                    plugin.adminActions.updateSettingsConfig(admin, "apply.playtime_minutes", value)
                    ctx.msg(admin, "admin.settings.playtime_set", mapOf("value" to value.toString()))
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
                    plugin.adminActions.updateSettingsConfig(admin, "apply.cost", value)
                    ctx.msg(admin, "admin.settings.apply_cost_set", mapOf("value" to value.toString()))
                }
        )
    }

    private fun adminSetCandidateStatus(admin: Player, name: String, status: CandidateStatus) {
        val now = Instant.now()
        val term = ctx.plugin.termService.computeCached(now).second
        val entry = ctx.plugin.adminActions.findCandidateByName(term, name)

        if (entry == null) {
            ctx.msg(admin, "admin.candidate.not_found", mapOf("name" to name))
            val names = ctx.plugin.store.candidates(term, includeRemoved = true).map { it.lastKnownName }
            if (names.isNotEmpty()) {
                ctx.msg(admin, "admin.candidate.list", mapOf("names" to names.joinToString(", ")))
            }
            return
        }

        ctx.plugin.scope.launch(ctx.plugin.mainDispatcher) {
            ctx.plugin.adminActions.setCandidateStatus(admin, term, entry.uuid, status)
            ctx.msg(
                admin,
                "admin.candidate.status_set",
                mapOf(
                    "name" to entry.lastKnownName,
                    "status" to status.name,
                    "term" to (term + 1).toString()
                )
            )
        }
    }
}

