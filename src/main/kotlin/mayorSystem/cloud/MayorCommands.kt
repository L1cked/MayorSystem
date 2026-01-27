package mayorSystem.cloud

import mayorSystem.MayorPlugin
import mayorSystem.config.CustomRequestCondition
import mayorSystem.data.CandidateStatus
import mayorSystem.data.RequestStatus
import mayorSystem.security.Perms
import mayorSystem.ui.menus.*
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.incendo.cloud.paper.PaperCommandManager
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.paper.util.sender.Source
import org.incendo.cloud.parser.standard.IntegerParser.integerParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

class MayorCommands(
    private val plugin: MayorPlugin,
    private val cm: PaperCommandManager<Source>
) {

    fun register() {
        registerPublic()
        registerAdmin()
        registerLegacyAliases()
    }

    // ---------------------------------------------------------------------
    // Public commands
    // ---------------------------------------------------------------------

    private fun registerPublic() {
        // /mayor -> main menu (public gated)
        cm.command(
            cm.commandBuilder("mayor")
                .permission(Perms.USE)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val player: Player = ctx.sender().source()
                    if (!canUsePublicFeatures(player)) {
                        player.sendMessage("Mayor system is currently closed.")
                        return@handler
                    }
                    plugin.gui.open(player, MainMenu(plugin))
                }
        )

        // /mayor status -> status menu
        cm.command(
            cm.commandBuilder("mayor")
                .literal("status")
                .permission(Perms.USE)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val player: Player = ctx.sender().source()
                    if (!canUsePublicFeatures(player)) {
                        player.sendMessage("Mayor system is currently closed.")
                        return@handler
                    }
                    plugin.gui.open(player, StatusMenu(plugin))
                }
        )

        // /mayor apply -> start apply wizard
        cm.command(
            cm.commandBuilder("mayor")
                .literal("apply")
                .permission(Perms.APPLY)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val player: Player = ctx.sender().source()
                    if (!canUsePublicFeatures(player)) {
                        player.sendMessage("Mayor system is currently closed.")
                        return@handler
                    }
                    handleApply(player)
                }
        )

        // /mayor vote <candidateName>
        cm.command(
            cm.commandBuilder("mayor")
                .literal("vote")
                .permission(Perms.VOTE)
                .senderType(PlayerSource::class.java)
                .required("candidate", stringParser())
                .handler { ctx ->
                    val player: Player = ctx.sender().source()
                    if (!canUsePublicFeatures(player)) {
                        player.sendMessage("Mayor system is currently closed.")
                        return@handler
                    }
                    val name = ctx.get<String>("candidate")
                    handleVote(player, name)
                }
        )
    }

    // ---------------------------------------------------------------------
    // Admin commands (new structure)
    // /mayor admin <section> <action> ...
    // ---------------------------------------------------------------------

    private fun registerAdmin() {
        // /mayor admin -> open admin panel (also acts as permission gate so Brigadier can hide it)
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .permission(Perms.ADMIN_ACCESS)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    plugin.gui.open(admin, AdminMenu(plugin))
                }
        )

        // /mayor admin panel -> alias
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("panel")
                .permission(Perms.ADMIN_ACCESS)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    plugin.gui.open(admin, AdminMenu(plugin))
                }
        )

        // System
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("system")
                .literal("toggle")
                .permission(Perms.ADMIN_SYSTEM_TOGGLE)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin: Player = ctx.sender().source()
                    togglePublicAccess(admin)
                }
        )

        // Candidates
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("candidates")
                .literal("remove")
                .permission(Perms.ADMIN_CANDIDATES_REMOVE)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    adminSetCandidateStatus(admin, name, CandidateStatus.REMOVED)
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("candidates")
                .literal("restore")
                .permission(Perms.ADMIN_CANDIDATES_RESTORE)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    adminSetCandidateStatus(admin, name, CandidateStatus.ACTIVE)
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("candidates")
                .literal("process")
                .permission(Perms.ADMIN_CANDIDATES_PROCESS)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    adminSetCandidateStatus(admin, name, CandidateStatus.PROCESS)
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("candidates")
                .literal("applyban")
                .literal("perm")
                .permission(Perms.ADMIN_CANDIDATES_APPLYBAN)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    val uuid = off.uniqueId
                    val resolvedName = off.name ?: name
                    plugin.adminActions.setApplyBanPermanent(admin, uuid, resolvedName)
                    admin.sendMessage("$resolvedName permanently banned from applying.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("candidates")
                .literal("applyban")
                .literal("temp")
                .permission(Perms.ADMIN_CANDIDATES_APPLYBAN)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .required("days", integerParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    val days = ctx.get<Int>("days").coerceAtLeast(1)
                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    val uuid = off.uniqueId
                    val resolvedName = off.name ?: name
                    val until = OffsetDateTime.now().plusDays(days.toLong())
                    plugin.adminActions.setApplyBanTemp(admin, uuid, resolvedName, until)
                    admin.sendMessage("$resolvedName temp-banned from applying for $days day(s).")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("candidates")
                .literal("applyban")
                .literal("clear")
                .permission(Perms.ADMIN_CANDIDATES_APPLYBAN)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    plugin.adminActions.clearApplyBan(admin, off.uniqueId)
                    admin.sendMessage("Apply ban cleared for ${off.name ?: name}.")
                }
        )

        // Perks: refresh
        // - No args: opens the refresh menu
        // - With args: --all | playerName
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perks")
                .literal("refresh")
                .permission(Perms.ADMIN_PERKS_REFRESH)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    plugin.gui.open(admin, AdminPerkRefreshMenu(plugin))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perks")
                .literal("refresh")
                .permission(Perms.ADMIN_PERKS_REFRESH)
                .senderType(PlayerSource::class.java)
                .required("target", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val target = ctx.get<String>("target")

                    if (target.equals("--all", ignoreCase = true) || target.equals("all", ignoreCase = true)) {
                        val count = plugin.adminActions.refreshPerksAll(admin)
                        admin.sendMessage("Refreshed perk effects for $count online player(s).")
                        return@handler
                    }

                    val p = findOnlinePlayer(target)
                    if (p == null) {
                        admin.sendMessage("Player not found online: $target")
                        sendOnlinePlayers(admin)
                        return@handler
                    }

                    plugin.adminActions.refreshPerksPlayer(admin, p)
                    admin.sendMessage("Refreshed perk effects for ${p.name}.")
                }
        )

        // Perks: requests approve/deny
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perks")
                .literal("requests")
                .literal("approve")
                .permission(Perms.ADMIN_PERKS_REQUESTS)
                .senderType(PlayerSource::class.java)
                .required("id", integerParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val id = ctx.get<Int>("id")
                    val term = plugin.termService.compute(Instant.now()).second
                    plugin.adminActions.setRequestStatus(admin, term, id, RequestStatus.APPROVED)
                    admin.sendMessage("Approved request #$id.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perks")
                .literal("requests")
                .literal("deny")
                .permission(Perms.ADMIN_PERKS_REQUESTS)
                .senderType(PlayerSource::class.java)
                .required("id", integerParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val id = ctx.get<Int>("id")
                    val term = plugin.termService.compute(Instant.now()).second
                    plugin.adminActions.setRequestStatus(admin, term, id, RequestStatus.DENIED)
                    admin.sendMessage("Denied request #$id.")
                }
        )

        // Perks: catalog toggles
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perks")
                .literal("catalog")
                .literal("section")
                .permission(Perms.ADMIN_PERKS_CATALOG)
                .senderType(PlayerSource::class.java)
                .required("section", stringParser())
                .required("state", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val section = ctx.get<String>("section")
                    val state = ctx.get<String>("state")

                    val base = "perks.sections.$section"
                    if (!plugin.config.contains(base)) {
                        admin.sendMessage("Section not found: $section")
                        return@handler
                    }

                    val current = plugin.config.getBoolean("$base.enabled", true)
                    val next = resolveToggle(state, current) ?: run {
                        admin.sendMessage("State must be: toggle/on/off")
                        return@handler
                    }

                    plugin.adminActions.setPerkSectionEnabled(admin, section, next)
                    admin.sendMessage("Section $section is now ${if (next) "ENABLED" else "DISABLED"}.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perks")
                .literal("catalog")
                .literal("perk")
                .permission(Perms.ADMIN_PERKS_CATALOG)
                .senderType(PlayerSource::class.java)
                .required("section", stringParser())
                .required("perk", stringParser())
                .required("state", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val section = ctx.get<String>("section")
                    val perk = ctx.get<String>("perk")
                    val state = ctx.get<String>("state")

                    val base = "perks.sections.$section.perks.$perk"
                    if (!plugin.config.contains(base)) {
                        admin.sendMessage("Perk not found: $section/$perk")
                        return@handler
                    }

                    val current = plugin.config.getBoolean("$base.enabled", true)
                    val next = resolveToggle(state, current) ?: run {
                        admin.sendMessage("State must be: toggle/on/off")
                        return@handler
                    }

                    plugin.adminActions.setPerkEnabled(admin, section, perk, next)
                    admin.sendMessage("Perk $section/$perk is now ${if (next) "ENABLED" else "DISABLED"}.")
                }
        )

        // Election start/end/clear
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("election")
                .literal("start")
                .permission(Perms.ADMIN_ELECTION_START)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val ok = plugin.adminActions.forceStartElectionNow(admin)
                    admin.sendMessage(if (ok) "Election started early (schedule shifted)." else "Failed to start election.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("election")
                .literal("end")
                .permission(Perms.ADMIN_ELECTION_END)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val ok = plugin.adminActions.forceEndElectionNow(admin)
                    admin.sendMessage(if (ok) "Election ended early. New term started." else "Failed to end election.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("election")
                .literal("clear")
                .permission(Perms.ADMIN_ELECTION_CLEAR)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val term = plugin.termService.compute(Instant.now()).second
                    plugin.adminActions.clearAllOverridesForTerm(admin, term)
                    admin.sendMessage("Cleared admin overrides for term #${term + 1}.")
                }
        )

        // Election elect set/clear/now
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("election")
                .literal("elect")
                .literal("set")
                .permission(Perms.ADMIN_ELECTION_ELECT)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    val uuid = off.uniqueId
                    val resolvedName = off.name ?: name
                    val electionTerm = plugin.termService.compute(Instant.now()).second
                    plugin.adminActions.setForcedMayor(admin, electionTerm, uuid, resolvedName)
                    admin.sendMessage("Forced mayor set for term #${electionTerm + 1}: $resolvedName.")
                    admin.sendMessage("(This does not start the term yet.)")
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
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val electionTerm = plugin.termService.compute(Instant.now()).second
                    plugin.adminActions.clearForcedMayor(admin, electionTerm)
                    admin.sendMessage("Cleared forced mayor for term #${electionTerm + 1}.")
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
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")

                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    val uuid = off.uniqueId
                    val resolvedName = off.name ?: name

                    val electionTerm = plugin.termService.compute(Instant.now()).second
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

        // Settings
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("enabled")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val value = parseBool(ctx.get<String>("value")) ?: run {
                        admin.sendMessage("Value must be true/false.")
                        return@handler
                    }
                    plugin.adminActions.updateConfig(admin, "enabled", value)
                    admin.sendMessage("Plugin enabled set to $value.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("term_length")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val raw = ctx.get<String>("value")
                    val duration = runCatching { Duration.parse(raw) }.getOrNull()
                    if (duration == null) {
                        admin.sendMessage("Invalid duration. Use ISO-8601 like P14D.")
                        return@handler
                    }
                    plugin.adminActions.updateConfig(admin, "term.length", duration.toString())
                    admin.sendMessage("Term length set to $duration.")
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
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val raw = ctx.get<String>("value")
                    val duration = runCatching { Duration.parse(raw) }.getOrNull()
                    if (duration == null) {
                        admin.sendMessage("Invalid duration. Use ISO-8601 like P3D.")
                        return@handler
                    }
                    plugin.adminActions.updateConfig(admin, "term.vote_window", duration.toString())
                    admin.sendMessage("Vote window set to $duration.")
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
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val raw = ctx.get<String>("value")
                    val dt = runCatching { OffsetDateTime.parse(raw) }.getOrNull()
                    if (dt == null) {
                        admin.sendMessage("Invalid date-time. Use ISO-8601 with offset.")
                        return@handler
                    }
                    plugin.adminActions.updateConfig(admin, "term.first_term_start", dt.toString())
                    admin.sendMessage("First term start set to $dt.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("playtime_minutes")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", integerParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val value = ctx.get<Int>("value").coerceAtLeast(0)
                    plugin.adminActions.updateConfig(admin, "apply.playtime_minutes", value)
                    admin.sendMessage("Apply playtime minutes set to $value.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("apply_cost")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val raw = ctx.get<String>("value")
                    val value = raw.toDoubleOrNull()
                    if (value == null || value < 0.0) {
                        admin.sendMessage("Invalid cost.")
                        return@handler
                    }
                    plugin.adminActions.updateConfig(admin, "apply.cost", value)
                    admin.sendMessage("Apply cost set to $value.")
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
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val value = ctx.get<Int>("value").coerceAtLeast(0)
                    plugin.adminActions.updateConfig(admin, "term.perks_per_term", value)
                    admin.sendMessage("Perks per term set to $value.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("custom_limit")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", integerParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val value = ctx.get<Int>("value").coerceAtLeast(0)
                    plugin.adminActions.updateConfig(admin, "custom_requests.limit_per_term", value)
                    admin.sendMessage("Custom request limit set to $value.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("custom_condition")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val raw = ctx.get<String>("value").uppercase()
                    val cond = runCatching { CustomRequestCondition.valueOf(raw) }.getOrNull()
                    if (cond == null) {
                        admin.sendMessage("Invalid condition. Use NONE, ELECTED_ONCE, or APPLY_REQUIREMENTS.")
                        return@handler
                    }
                    plugin.adminActions.updateConfig(admin, "custom_requests.request_condition", cond.name)
                    admin.sendMessage("Custom request condition set to ${cond.name}.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("bonus_enabled")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val value = parseBool(ctx.get<String>("value")) ?: run {
                        admin.sendMessage("Value must be true/false.")
                        return@handler
                    }
                    plugin.adminActions.updateConfig(admin, "term.bonus_term.enabled", value)
                    admin.sendMessage("Bonus terms enabled set to $value.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("bonus_every")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", integerParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val value = ctx.get<Int>("value").coerceAtLeast(1)
                    plugin.adminActions.updateConfig(admin, "term.bonus_term.every_x_terms", value)
                    admin.sendMessage("Bonus term interval set to $value.")
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("bonus_perks")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", integerParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val value = ctx.get<Int>("value").coerceAtLeast(1)
                    plugin.adminActions.updateConfig(admin, "term.bonus_term.perks_per_bonus_term", value)
                    admin.sendMessage("Bonus perks set to $value.")
                }
        )

        // Reload
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("reload")
                .permission(Perms.ADMIN_SETTINGS_RELOAD)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    plugin.adminActions.reload(admin)
                    admin.sendMessage("Reloaded config and elections store.")
                }
        )

        // Audit / Health (open menus)
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("audit")
                .permission(Perms.ADMIN_AUDIT_VIEW)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    plugin.gui.open(admin, AdminAuditMenu(plugin))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("health")
                .permission(Perms.ADMIN_HEALTH_VIEW)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    plugin.gui.open(admin, AdminHealthMenu(plugin))
                }
        )


// Mayor NPC
cm.command(
    cm.commandBuilder("mayor")
        .literal("admin")
        .literal("npc")
        .literal("spawn")
        .permission(Perms.ADMIN_NPC_MAYOR)
        .senderType(PlayerSource::class.java)
        .handler { ctx ->
            val admin = ctx.sender().source()
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
        .handler { ctx ->
            val admin = ctx.sender().source()
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
        .handler { ctx ->
            val admin = ctx.sender().source()
            plugin.mayorNpc.forceUpdate(admin)
        }
)
    }

    // ---------------------------------------------------------------------
    // Legacy aliases (keep existing servers from breaking)
    // ---------------------------------------------------------------------

    private fun registerLegacyAliases() {
        // /mayor toggle -> same as /mayor admin system toggle
        cm.command(
            cm.commandBuilder("mayor")
                .literal("toggle")
                .permission(Perms.ADMIN_SYSTEM_TOGGLE)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    togglePublicAccess(admin)
                }
        )

        // Old shortcuts kept (the new ones are the canonical ones)
        // /mayor admin remove|restore|process <player>
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("remove")
                .permission(Perms.ADMIN_CANDIDATES_REMOVE)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    adminSetCandidateStatus(admin, name, CandidateStatus.REMOVED)
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("restore")
                .permission(Perms.ADMIN_CANDIDATES_RESTORE)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    adminSetCandidateStatus(admin, name, CandidateStatus.ACTIVE)
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("process")
                .permission(Perms.ADMIN_CANDIDATES_PROCESS)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    adminSetCandidateStatus(admin, name, CandidateStatus.PROCESS)
                }
        )

        // /mayor admin perk approve|deny <id> (old path)
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perk")
                .literal("approve")
                .permission(Perms.ADMIN_PERKS_REQUESTS)
                .senderType(PlayerSource::class.java)
                .required("id", integerParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val id = ctx.get<Int>("id")
                    val term = plugin.termService.compute(Instant.now()).second
                    plugin.adminActions.setRequestStatus(admin, term, id, RequestStatus.APPROVED)
                    admin.sendMessage("Approved request #$id.")
                }
        )
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perk")
                .literal("deny")
                .permission(Perms.ADMIN_PERKS_REQUESTS)
                .senderType(PlayerSource::class.java)
                .required("id", integerParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val id = ctx.get<Int>("id")
                    val term = plugin.termService.compute(Instant.now()).second
                    plugin.adminActions.setRequestStatus(admin, term, id, RequestStatus.DENIED)
                    admin.sendMessage("Denied request #$id.")
                }
        )

        // /mayor admin perks section/perk ... (old path)
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perks")
                .literal("section")
                .permission(Perms.ADMIN_PERKS_CATALOG)
                .senderType(PlayerSource::class.java)
                .required("section", stringParser())
                .required("state", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val section = ctx.get<String>("section")
                    val state = ctx.get<String>("state")
                    val base = "perks.sections.$section"
                    if (!plugin.config.contains(base)) {
                        admin.sendMessage("Section not found: $section")
                        return@handler
                    }
                    val current = plugin.config.getBoolean("$base.enabled", true)
                    val next = resolveToggle(state, current) ?: run {
                        admin.sendMessage("State must be: toggle/on/off")
                        return@handler
                    }
                    plugin.adminActions.setPerkSectionEnabled(admin, section, next)
                    admin.sendMessage("Section $section is now ${if (next) "ENABLED" else "DISABLED"}.")
                }
        )
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perks")
                .literal("perk")
                .permission(Perms.ADMIN_PERKS_CATALOG)
                .senderType(PlayerSource::class.java)
                .required("section", stringParser())
                .required("perk", stringParser())
                .required("state", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val section = ctx.get<String>("section")
                    val perk = ctx.get<String>("perk")
                    val state = ctx.get<String>("state")
                    val base = "perks.sections.$section.perks.$perk"
                    if (!plugin.config.contains(base)) {
                        admin.sendMessage("Perk not found: $section/$perk")
                        return@handler
                    }
                    val current = plugin.config.getBoolean("$base.enabled", true)
                    val next = resolveToggle(state, current) ?: run {
                        admin.sendMessage("State must be: toggle/on/off")
                        return@handler
                    }
                    plugin.adminActions.setPerkEnabled(admin, section, perk, next)
                    admin.sendMessage("Perk $section/$perk is now ${if (next) "ENABLED" else "DISABLED"}.")
                }
        )

        // /mayor admin elect ... (old path)
        // Note: we register the more specific literals (clear/now) first so they don't get swallowed
        // by the generic <player> version.
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("elect")
                .literal("clear")
                .permission(Perms.ADMIN_ELECTION_ELECT)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val electionTerm = plugin.termService.compute(Instant.now()).second
                    plugin.adminActions.clearForcedMayor(admin, electionTerm)
                    admin.sendMessage("Cleared forced mayor for term #${electionTerm + 1}.")
                }
        )
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("elect")
                .literal("now")
                .permission(Perms.ADMIN_ELECTION_ELECT)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")

                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    val uuid = off.uniqueId
                    val resolvedName = off.name ?: name

                    val electionTerm = plugin.termService.compute(Instant.now()).second
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

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("elect")
                .permission(Perms.ADMIN_ELECTION_ELECT)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    val uuid = off.uniqueId
                    val resolvedName = off.name ?: name
                    val electionTerm = plugin.termService.compute(Instant.now()).second
                    plugin.adminActions.setForcedMayor(admin, electionTerm, uuid, resolvedName)
                    admin.sendMessage("Forced mayor set for term #${electionTerm + 1}: $resolvedName.")
                    admin.sendMessage("(This does not start the term yet.)")
                }
        )

        // /mayor admin reload (old path) -> maps to /mayor admin settings reload
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("reload")
                .permission(Perms.ADMIN_SETTINGS_RELOAD)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    plugin.adminActions.reload(admin)
                    admin.sendMessage("Reloaded config and elections store.")
                }
        )
    }

    // ---------------------------------------------------------------------
    // Shared logic helpers
    // ---------------------------------------------------------------------

    /**
     * Public gating rules:
     * - enabled=false -> nobody can use anything (including admins)
     * - public_enabled=false -> only staff can use public/player features
     */
    private fun canUsePublicFeatures(player: Player): Boolean {
        if (!plugin.settings.enabled) return false
        if (plugin.settings.publicEnabled) return true
        return Perms.isAdmin(player)
    }

    private fun togglePublicAccess(admin: Player) {
        val masterEnabled = plugin.settings.enabled
        if (!masterEnabled) {
            admin.sendMessage("Mayor system master switch is OFF (enabled=false). Turn it on in config.yml first.")
            return
        }

        val current = plugin.settings.publicEnabled
        val next = !current
        plugin.adminActions.updateConfig(admin, "public_enabled", next)

        if (next) {
            admin.sendMessage("Mayor system is now ENABLED for regular players.")
        } else {
            admin.sendMessage("Mayor system is now DISABLED for regular players (staff still have access).")
        }
    }

    private fun handleApply(player: Player) {
        val s = plugin.settings
        if (!s.enabled) {
            player.sendMessage("Mayor system is disabled.")
            return
        }

        val now = Instant.now()
        val electionTerm = plugin.termService.compute(now).second
        if (!isElectionOpen(now, electionTerm)) {
            player.sendMessage("Applications are closed right now.")
            return
        }

        val playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE)
        val minTicks = s.applyPlaytimeMinutes * 60 * 20
        if (playTicks < minTicks) {
            player.sendMessage("Not enough playtime to apply. Need ${s.applyPlaytimeMinutes} minutes.")
            return
        }

        if (plugin.store.isCandidate(electionTerm, player.uniqueId)) {
            player.sendMessage("You already applied for term #${electionTerm + 1}.")
            plugin.gui.open(player, CandidateMenu(plugin))
            return
        }

        plugin.applyFlow.start(player, electionTerm)
        plugin.gui.open(player, ApplySectionsMenu(plugin))
    }

    private fun handleVote(player: Player, candidateName: String) {
        val now = Instant.now()
        val electionTerm = plugin.termService.compute(now).second

        if (!isElectionOpen(now, electionTerm)) {
            player.sendMessage("Voting is closed.")
            return
        }

        val allowChange = plugin.settings.allowVoteChange
        if (plugin.store.hasVoted(electionTerm, player.uniqueId) && !allowChange) {
            player.sendMessage("You already voted this term.")
            return
        }

        val candidate = plugin.store.candidates(electionTerm, includeRemoved = false)
            .firstOrNull { it.lastKnownName.equals(candidateName, ignoreCase = true) }

        if (candidate == null) {
            player.sendMessage("Candidate not found: $candidateName")
            val available = plugin.store.candidates(electionTerm, includeRemoved = false)
                .filter { it.status == CandidateStatus.ACTIVE }
                .map { it.lastKnownName }
            if (available.isNotEmpty()) {
                player.sendMessage("Available candidates: ${available.joinToString(", ")}")
            }
            return
        }

        if (candidate.status != CandidateStatus.ACTIVE) {
            player.sendMessage("That candidate is currently in process and cannot receive votes right now.")
            return
        }

        val prev = plugin.store.votedFor(electionTerm, player.uniqueId)
        plugin.store.vote(electionTerm, player.uniqueId, candidate.uuid)

        if (prev == null) {
            player.sendMessage("Vote cast for ${candidate.lastKnownName}.")
        } else {
            player.sendMessage("Vote updated to ${candidate.lastKnownName}.")
        }
    }

    private fun adminSetCandidateStatus(admin: Player, name: String, status: CandidateStatus) {
        val now = Instant.now()
        val term = plugin.termService.compute(now).second
        val entry = plugin.adminActions.findCandidateByName(term, name)

        if (entry == null) {
            admin.sendMessage("Candidate not found: $name")
            val names = plugin.store.candidates(term, includeRemoved = true).map { it.lastKnownName }
            if (names.isNotEmpty()) {
                admin.sendMessage("Candidates this term: ${names.joinToString(", ")}")
            }
            return
        }

        plugin.adminActions.setCandidateStatus(admin, term, entry.uuid, status)
        admin.sendMessage("${entry.lastKnownName} set to $status for term #${term + 1}.")
    }

    /**
     * Election open check with admin override:
     * admin.election_override.<term> = OPEN/CLOSED
     */
    private fun isElectionOpen(now: Instant, term: Int): Boolean {
        return when (plugin.config.getString("admin.election_override.$term")?.uppercase()) {
            "OPEN" -> true
            "CLOSED" -> false
            else -> plugin.termService.isElectionOpen(now, term)
        }
    }

    private fun parseBool(input: String): Boolean? {
        return when (input.lowercase()) {
            "true", "on", "yes", "1" -> true
            "false", "off", "no", "0" -> false
            else -> null
        }
    }

    private fun resolveToggle(input: String, current: Boolean): Boolean? {
        return when (input.lowercase()) {
            "toggle", "t" -> !current
            "on", "enable", "enabled", "true", "yes" -> true
            "off", "disable", "disabled", "false", "no" -> false
            else -> null
        }
    }

    private fun findOnlinePlayer(name: String): Player? {
        // Exact is best, but we also accept case-insensitive matches.
        return Bukkit.getPlayerExact(name)
            ?: Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun sendOnlinePlayers(to: Player) {
        val names = Bukkit.getOnlinePlayers().map { it.name }.sortedBy { it.lowercase() }
        if (names.isEmpty()) {
            to.sendMessage("Online players: (none)")
            return
        }

        val shown = names.take(25)
        val suffix = if (names.size > shown.size) " ... (+${names.size - shown.size})" else ""
        to.sendMessage("Online players: ${shown.joinToString(", ")}$suffix")
    }
}
