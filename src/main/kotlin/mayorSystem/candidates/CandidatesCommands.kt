package mayorSystem.candidates

import mayorSystem.cloud.CommandContext
import mayorSystem.candidates.ui.AdminApplyBanSearchMenu
import mayorSystem.candidates.ui.AdminCandidatesMenu
import mayorSystem.candidates.ui.AdminSettingsApplyMenu
import mayorSystem.data.CandidateStatus
import mayorSystem.security.Perms
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.IntegerParser.integerParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider
import mayorSystem.util.BukkitCompat
import java.time.OffsetDateTime
import java.time.Instant
import kotlinx.coroutines.launch

class CandidatesCommands(private val ctx: CommandContext) {
    private val onlinePlayerSuggestions = SuggestionProvider.blockingStrings<CommandSender> { _, _ ->
        Bukkit.getOnlinePlayers().map { it.name }.sortedBy { it.lowercase() }
    }

    private fun resolveProfile(name: String, callback: (uuid: java.util.UUID, resolvedName: String) -> Unit) {
        val plugin = ctx.plugin
        val off = BukkitCompat.getOfflinePlayer(plugin.server, name)
        callback(off.uniqueId, off.name ?: name)
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
                .senderType(Player::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender()
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
                .senderType(Player::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender()
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
                .senderType(Player::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender()
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
                .senderType(Player::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender()
                    val name = command.get<String>("player")
                    resolveProfile(name) { uuid, resolvedName ->
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
                .senderType(Player::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .required("days", integerParser())
                .handler { command ->
                    val admin = command.sender()
                    val name = command.get<String>("player")
                    val days = command.get<Int>("days").coerceAtLeast(1)
                    val until = OffsetDateTime.now().plusDays(days.toLong())
                    resolveProfile(name) { uuid, resolvedName ->
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
                .senderType(Player::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { command ->
                    val admin = command.sender()
                    val name = command.get<String>("player")
                    resolveProfile(name) { uuid, resolvedName ->
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
                .senderType(Player::class.java)
                .required("value", integerParser())
                .handler { command ->
                    val admin = command.sender()
                    val value = command.get<Int>("value").coerceAtLeast(0)
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
                .senderType(Player::class.java)
                .required("value", stringParser())
                .handler { command ->
                    val admin = command.sender()
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
                                mapOf("value" to value.toString())
                            )
                        )
                    }
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
            ctx.dispatch(
                admin,
                ctx.plugin.adminActions.setCandidateStatus(admin, term, entry.uuid, status, entry.lastKnownName)
            )
        }
    }
}

