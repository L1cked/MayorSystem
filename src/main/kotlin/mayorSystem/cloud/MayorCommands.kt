package mayorSystem.cloud

import mayorSystem.MayorPlugin
import mayorSystem.config.CustomRequestCondition
import mayorSystem.data.CandidateStatus
import mayorSystem.data.RequestStatus
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import mayorSystem.ui.menus.*
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.paper.PaperCommandManager
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.paper.util.sender.Source
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.IntegerParser.integerParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider
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

    private val voteCooldown = Duration.ofSeconds(2)
    private val applyCooldown = Duration.ofSeconds(2)
    private val cooldowns = mutableMapOf<String, MutableMap<java.util.UUID, Long>>()

    private val candidateSuggestions = SuggestionProvider.blockingStrings<Source> { _, _ ->
        val term = plugin.termService.computeNow().second
        plugin.store.candidates(term, includeRemoved = false)
            .filter { it.status == CandidateStatus.ACTIVE }
            .map { it.lastKnownName }
    }

    private val onlinePlayerSuggestions = SuggestionProvider.blockingStrings<Source> { _, _ ->
        Bukkit.getOnlinePlayers().map { it.name }.sortedBy { it.lowercase() }
    }

    private val perkSectionSuggestions = SuggestionProvider.blockingStrings<Source> { _, _ ->
        plugin.config.getConfigurationSection("perks.sections")
            ?.getKeys(false)
            ?.toList()
            ?: emptyList()
    }

    private val perkSuggestions = SuggestionProvider.blockingStrings<Source> { ctx, _ ->
        val section = runCatching { ctx.get<String>("section") }.getOrNull()
        val base = if (section.isNullOrBlank()) "perks.sections" else "perks.sections.$section.perks"
        val sec = plugin.config.getConfigurationSection(base) ?: return@blockingStrings emptyList()
        if (section.isNullOrBlank()) {
            sec.getKeys(false).flatMap { sectionId ->
                plugin.config.getConfigurationSection("perks.sections.$sectionId.perks")
                    ?.getKeys(false)
                    ?.toList()
                    ?: emptyList()
            }
        } else {
            sec.getKeys(false).toList()
        }
    }

    private val stateSuggestions = SuggestionProvider.suggestingStrings<Source>("toggle", "on", "off")

    private val requestIdSuggestions = SuggestionProvider.blockingStrings<Source> { _, _ ->
        val term = plugin.termService.computeNow().second
        plugin.store.listRequests(term, RequestStatus.PENDING).map { it.id.toString() }
    }

    private val approveDenySuggestions = SuggestionProvider.suggestingStrings<Source>("approve", "deny")

    private val refreshTargetSuggestions = SuggestionProvider.blockingStrings<Source> { _, _ ->
        val names = Bukkit.getOnlinePlayers().map { it.name }.sortedBy { it.lowercase() }
        listOf("--all", "all") + names
    }

    private val chatPromptKeySuggestions = SuggestionProvider.suggestingStrings<Source>(
        listOf("bio", "title", "description")
    )

    private val customConditionSuggestions = SuggestionProvider.suggestingStrings<Source>(
        CustomRequestCondition.values().map { it.name }
    )

    private val adminMenuIdSuggestions = SuggestionProvider.suggestingStrings<Source>(AdminMenuId.ids())

    // ---------------------------------------------------------------------
    // Public commands
    // ---------------------------------------------------------------------

    private fun registerPublic() {
        // /mayor -> main menu (public gated)
        registerMenuRoute(
            literals = emptyList(),
            permission = Permission.of(Perms.USE),
            menuFactory = { MainMenu(plugin) },
            requirePublicAccess = true
        )

        // /mayor status -> status menu
        registerMenuRoute(
            literals = listOf("status"),
            permission = Permission.of(Perms.USE),
            menuFactory = { StatusMenu(plugin) },
            requirePublicAccess = true
        )

        // /mayor apply -> start apply wizard
        cm.command(
            cm.commandBuilder("mayor")
                .literal("apply")
                .permission(Perms.APPLY)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val player: Player = ctx.sender().source()
                    withPublicAccess(player) {
                        if (checkCooldown(player, "apply", applyCooldown)) return@withPublicAccess
                        handleApply(player)
                    }
                }
        )

        // /mayor vote <candidateName>
        cm.command(
            cm.commandBuilder("mayor")
                .literal("vote")
                .permission(Perms.VOTE)
                .senderType(PlayerSource::class.java)
                .required("candidate", stringParser(), candidateSuggestions)
                .handler { ctx ->
                    val player: Player = ctx.sender().source()
                    withPublicAccess(player) {
                        if (checkCooldown(player, "vote", voteCooldown)) return@withPublicAccess
                        val name = ctx.get<String>("candidate")
                        handleVote(player, name)
                    }
                }
        )

        // /mayor vote -> open vote menu (fallback)
        registerMenuRoute(
            literals = listOf("vote"),
            permission = Permission.of(Perms.VOTE),
            menuFactory = { VoteMenu(plugin) },
            requirePublicAccess = true,
            cooldownKey = "vote",
            cooldown = voteCooldown
        )

        // /mayor candidate -> open candidate menu
        registerMenuRoute(
            literals = listOf("candidate"),
            permission = Permission.of(Perms.CANDIDATE),
            menuFactory = { CandidateMenu(plugin) },
            requirePublicAccess = true
        )

        // /mayor stepdown -> open step-down confirmation (only if election open + candidate)
        cm.command(
            cm.commandBuilder("mayor")
                .literal("stepdown")
                .permission(Perms.CANDIDATE)
                .senderType(PlayerSource::class.java)
                .handler { ctx ->
                    val player: Player = ctx.sender().source()
                    withPublicAccess(player) {
                        handleStepDown(player)
                    }
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
                .handler { ctx ->
                    val sender = ctx.sender().source()
                    withPlayer(sender) { admin ->
                        plugin.gui.open(admin, AdminMenu(plugin))
                    }
                }
        )

        // /mayor admin open <menuId>
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("open")
                .permission(Perms.ADMIN_ACCESS)
                .required("menuId", stringParser(), adminMenuIdSuggestions)
                .handler { ctx ->
                    val sender = ctx.sender().source()
                    val player = sender as? Player
                    if (player == null) {
                        msg(sender, "errors.player_only")
                        return@handler
                    }

                    val raw = ctx.get<String>("menuId")
                    val menuId = AdminMenuId.fromId(raw)
                    if (menuId == null) {
                        msg(player, "admin.open.invalid_menu", mapOf("id" to raw))
                        msg(player, "admin.open.available", mapOf("ids" to AdminMenuId.ids().joinToString(", ")))
                        return@handler
                    }

                    plugin.gui.open(player, menuId.factory(plugin))
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
        registerMenuRoute(
            literals = listOf("admin", "candidates"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_CANDIDATES_REMOVE),
                Permission.of(Perms.ADMIN_CANDIDATES_RESTORE),
                Permission.of(Perms.ADMIN_CANDIDATES_PROCESS),
                Permission.of(Perms.ADMIN_CANDIDATES_APPLYBAN)
            ),
            menuFactory = { AdminCandidatesMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "candidates", "remove"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_REMOVE),
            menuFactory = { AdminCandidatesMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "candidates", "restore"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_RESTORE),
            menuFactory = { AdminCandidatesMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "candidates", "process"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_PROCESS),
            menuFactory = { AdminCandidatesMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "candidates", "applyban"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_APPLYBAN),
            menuFactory = { AdminApplyBanSearchMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "candidates", "applyban", "perm"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_APPLYBAN),
            menuFactory = { AdminApplyBanSearchMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "candidates", "applyban", "temp"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_APPLYBAN),
            menuFactory = { AdminApplyBanSearchMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "candidates", "applyban", "clear"),
            permission = Permission.of(Perms.ADMIN_CANDIDATES_APPLYBAN),
            menuFactory = { AdminApplyBanSearchMenu(plugin) }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("candidates")
                .literal("remove")
                .permission(Perms.ADMIN_CANDIDATES_REMOVE)
                .senderType(PlayerSource::class.java)
                .required("player", stringParser(), onlinePlayerSuggestions)
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
                .required("player", stringParser(), onlinePlayerSuggestions)
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
                .required("player", stringParser(), onlinePlayerSuggestions)
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
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    val uuid = off.uniqueId
                    val resolvedName = off.name ?: name
                    plugin.adminActions.setApplyBanPermanent(admin, uuid, resolvedName)
                    msg(admin, "admin.applyban.permanent", mapOf("name" to resolvedName))
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
                .required("player", stringParser(), onlinePlayerSuggestions)
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
                    msg(admin, "admin.applyban.temp", mapOf("name" to resolvedName, "days" to days.toString()))
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
                .required("player", stringParser(), onlinePlayerSuggestions)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    plugin.adminActions.clearApplyBan(admin, off.uniqueId)
                    msg(admin, "admin.applyban.cleared", mapOf("name" to (off.name ?: name)))
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
                .handler { ctx ->
                    val sender = ctx.sender().source()
                    withPlayer(sender) { admin ->
                        plugin.gui.open(admin, AdminPerkRefreshMenu(plugin))
                    }
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perks")
                .literal("refresh")
                .permission(Perms.ADMIN_PERKS_REFRESH)
                .senderType(PlayerSource::class.java)
                .required("target", stringParser(), refreshTargetSuggestions)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val target = ctx.get<String>("target")

                    if (target.equals("--all", ignoreCase = true) || target.equals("all", ignoreCase = true)) {
                        val count = plugin.adminActions.refreshPerksAll(admin)
                        msg(admin, "admin.perks.refresh_all", mapOf("count" to count.toString()))
                        return@handler
                    }

                    val p = findOnlinePlayer(target)
                    if (p == null) {
                        msg(admin, "admin.perks.refresh_player_not_found", mapOf("name" to target))
                        sendOnlinePlayers(admin)
                        return@handler
                    }

                    plugin.adminActions.refreshPerksPlayer(admin, p)
                    msg(admin, "admin.perks.refresh_player", mapOf("name" to p.name))
                }
        )

        // Perks: requests approve/deny
        registerMenuRoute(
            literals = listOf("admin", "perks", "requests"),
            permission = Permission.of(Perms.ADMIN_PERKS_REQUESTS),
            menuFactory = { AdminPerkRequestsMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "perks", "requests", "approve"),
            permission = Permission.of(Perms.ADMIN_PERKS_REQUESTS),
            menuFactory = { AdminPerkRequestsMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "perks", "requests", "deny"),
            permission = Permission.of(Perms.ADMIN_PERKS_REQUESTS),
            menuFactory = { AdminPerkRequestsMenu(plugin) }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perks")
                .literal("requests")
                .literal("approve")
                .permission(Perms.ADMIN_PERKS_REQUESTS)
                .senderType(PlayerSource::class.java)
                .required("id", integerParser(), requestIdSuggestions)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val id = ctx.get<Int>("id")
                    val term = plugin.termService.computeNow().second
                    plugin.adminActions.setRequestStatus(admin, term, id, RequestStatus.APPROVED)
                    msg(admin, "admin.perks.request_approved", mapOf("id" to id.toString()))
                }
        )

        // /mayor admin customperk <id> <approve|deny>
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("customperk")
                .permission(Perms.ADMIN_PERKS_REQUESTS)
                .senderType(PlayerSource::class.java)
                .required("id", integerParser(), requestIdSuggestions)
                .required("action", stringParser(), approveDenySuggestions)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val id = ctx.get<Int>("id")
                    val action = ctx.get<String>("action").lowercase()
                    val term = plugin.termService.computeNow().second
                    val status = when (action) {
                        "approve" -> RequestStatus.APPROVED
                        "deny" -> RequestStatus.DENIED
                        else -> null
                    }
                    if (status == null) {
                        msg(admin, "admin.perks.request_action_invalid")
                        return@handler
                    }
                    plugin.adminActions.setRequestStatus(admin, term, id, status)
                    if (status == RequestStatus.APPROVED) {
                        msg(admin, "admin.perks.request_approved", mapOf("id" to id.toString()))
                    } else {
                        msg(admin, "admin.perks.request_denied", mapOf("id" to id.toString()))
                    }
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
                .required("id", integerParser(), requestIdSuggestions)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val id = ctx.get<Int>("id")
                    val term = plugin.termService.computeNow().second
                    plugin.adminActions.setRequestStatus(admin, term, id, RequestStatus.DENIED)
                    msg(admin, "admin.perks.request_denied", mapOf("id" to id.toString()))
                }
        )

        // Perks: catalog toggles
        registerMenuRoute(
            literals = listOf("admin", "perks", "catalog"),
            permission = Permission.of(Perms.ADMIN_PERKS_CATALOG),
            menuFactory = { AdminPerkCatalogMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "perks"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_PERKS_CATALOG),
                Permission.of(Perms.ADMIN_PERKS_REQUESTS),
                Permission.of(Perms.ADMIN_PERKS_REFRESH)
            ),
            menuFactory = { AdminPerkCatalogMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "customperk"),
            permission = Permission.of(Perms.ADMIN_PERKS_REQUESTS),
            menuFactory = { AdminPerkRequestsMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "perks", "catalog", "section"),
            permission = Permission.of(Perms.ADMIN_PERKS_CATALOG),
            menuFactory = { AdminPerkCatalogMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "perks", "catalog", "perk"),
            permission = Permission.of(Perms.ADMIN_PERKS_CATALOG),
            menuFactory = { AdminPerkCatalogMenu(plugin) }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("perks")
                .literal("catalog")
                .literal("section")
                .permission(Perms.ADMIN_PERKS_CATALOG)
                .senderType(PlayerSource::class.java)
                .required("section", stringParser(), perkSectionSuggestions)
                .required("state", stringParser(), stateSuggestions)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val section = ctx.get<String>("section")
                    val state = ctx.get<String>("state")

                    val base = "perks.sections.$section"
                    if (!plugin.config.contains(base)) {
                        msg(admin, "admin.perks.section_not_found", mapOf("section" to section))
                        return@handler
                    }

                    val current = plugin.config.getBoolean("$base.enabled", true)
                    val next = resolveToggle(state, current) ?: run {
                        msg(admin, "admin.perks.state_invalid")
                        return@handler
                    }

                    plugin.adminActions.setPerkSectionEnabled(admin, section, next)
                    msg(admin, "admin.perks.section_updated", mapOf("section" to section, "state" to if (next) "ENABLED" else "DISABLED"))
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
                .required("section", stringParser(), perkSectionSuggestions)
                .required("perk", stringParser(), perkSuggestions)
                .required("state", stringParser(), stateSuggestions)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val section = ctx.get<String>("section")
                    val perk = ctx.get<String>("perk")
                    val state = ctx.get<String>("state")

                    val base = "perks.sections.$section.perks.$perk"
                    if (!plugin.config.contains(base)) {
                        msg(admin, "admin.perks.perk_not_found", mapOf("section" to section, "perk" to perk))
                        return@handler
                    }

                    val current = plugin.config.getBoolean("$base.enabled", true)
                    val next = resolveToggle(state, current) ?: run {
                        msg(admin, "admin.perks.state_invalid")
                        return@handler
                    }

                    plugin.adminActions.setPerkEnabled(admin, section, perk, next)
                    msg(admin, "admin.perks.perk_updated", mapOf("section" to section, "perk" to perk, "state" to if (next) "ENABLED" else "DISABLED"))
                }
        )

        // Election start/end/clear
        registerMenuRoute(
            literals = listOf("admin", "election"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_ELECTION_START),
                Permission.of(Perms.ADMIN_ELECTION_END),
                Permission.of(Perms.ADMIN_ELECTION_CLEAR),
                Permission.of(Perms.ADMIN_ELECTION_ELECT)
            ),
            menuFactory = { AdminElectionMenu(plugin) }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("election")
                .literal("start")
                .permission(Perms.ADMIN_ELECTION_START)
                .handler { ctx ->
                    val sender = ctx.sender().source()
                    val ok = plugin.adminActions.forceStartElectionNow(sender as? Player)
                    if (ok) {
                        msg(sender, "admin.election.started")
                    } else {
                        msg(sender, "admin.election.start_failed")
                    }
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("election")
                .literal("end")
                .permission(Perms.ADMIN_ELECTION_END)
                .handler { ctx ->
                    val sender = ctx.sender().source()
                    val ok = plugin.adminActions.forceEndElectionNow(sender as? Player)
                    if (ok) {
                        msg(sender, "admin.election.ended")
                    } else {
                        msg(sender, "admin.election.end_failed")
                    }
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("election")
                .literal("clear")
                .permission(Perms.ADMIN_ELECTION_CLEAR)
                .handler { ctx ->
                    val sender = ctx.sender().source()
                    val term = plugin.termService.computeNow().second
                    plugin.adminActions.clearAllOverridesForTerm(sender as? Player, term)
                    msg(sender, "admin.election.overrides_cleared", mapOf("term" to (term + 1).toString()))
                }
        )

        // Election elect set/clear/now
        registerMenuRoute(
            literals = listOf("admin", "election", "elect"),
            permission = Permission.of(Perms.ADMIN_ELECTION_ELECT),
            menuFactory = { AdminForceElectMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "election", "elect", "set"),
            permission = Permission.of(Perms.ADMIN_ELECTION_ELECT),
            menuFactory = { AdminForceElectMenu(plugin) }
        )

        registerMenuRoute(
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
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")
                    val off = plugin.server.getOfflinePlayerIfCached(name) ?: plugin.server.getOfflinePlayer(name)
                    val uuid = off.uniqueId
                    val resolvedName = off.name ?: name
                    val electionTerm = plugin.termService.computeNow().second
                    plugin.adminActions.setForcedMayor(admin, electionTerm, uuid, resolvedName)
                    msg(admin, "admin.election.forced_mayor_set", mapOf("term" to (electionTerm + 1).toString(), "name" to resolvedName))
                    msg(admin, "admin.election.forced_mayor_hint")
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
                    val electionTerm = plugin.termService.computeNow().second
                    plugin.adminActions.clearForcedMayor(admin, electionTerm)
                    msg(admin, "admin.election.forced_mayor_cleared", mapOf("term" to (electionTerm + 1).toString()))
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
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val name = ctx.get<String>("player")

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

        // Settings
        registerMenuRoute(
            literals = listOf("admin", "settings"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_SETTINGS_EDIT),
                Permission.of(Perms.ADMIN_SETTINGS_RELOAD),
                Permission.of(Perms.ADMIN_PERKS_CATALOG)
            ),
            menuFactory = { AdminSettingsMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "enabled"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsGeneralMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "term_length"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "vote_window"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "first_term_start"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "perks_per_term"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "term_extras"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermExtrasMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "bonus_enabled"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermExtrasMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "bonus_every"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermExtrasMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "bonus_perks"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsTermExtrasMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "apply_cost"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsApplyMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "playtime_minutes"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsApplyMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "custom_limit"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsCustomRequestsMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "custom_condition"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsCustomRequestsMenu(plugin) }
        )

        registerMenuRoute(
            literals = listOf("admin", "settings", "chat_prompts"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsChatPromptsMenu(plugin) }
        )

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
                        msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "enabled", value)
                    msg(admin, "admin.settings.enabled_set", mapOf("value" to value.toString()))
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
                        msg(admin, "admin.settings.duration_invalid", mapOf("example" to "P14D"))
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "term.length", duration.toString())
                    msg(admin, "admin.settings.term_length_set", mapOf("value" to duration.toString()))
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
                        msg(admin, "admin.settings.duration_invalid", mapOf("example" to "P3D"))
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "term.vote_window", duration.toString())
                    msg(admin, "admin.settings.vote_window_set", mapOf("value" to duration.toString()))
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
                        msg(admin, "admin.settings.datetime_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "term.first_term_start", dt.toString())
                    msg(admin, "admin.settings.first_term_start_set", mapOf("value" to dt.toString()))
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
                    plugin.adminActions.updateSettingsConfig(admin, "apply.playtime_minutes", value)
                    msg(admin, "admin.settings.playtime_set", mapOf("value" to value.toString()))
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
                        msg(admin, "admin.settings.apply_cost_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "apply.cost", value)
                    msg(admin, "admin.settings.apply_cost_set", mapOf("value" to value.toString()))
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
                    plugin.adminActions.updateSettingsConfig(admin, "term.perks_per_term", value)
                    msg(admin, "admin.settings.perks_per_term_set", mapOf("value" to value.toString()))
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
                    plugin.adminActions.updateSettingsConfig(admin, "custom_requests.limit_per_term", value)
                    msg(admin, "admin.settings.custom_limit_set", mapOf("value" to value.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("custom_condition")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser(), customConditionSuggestions)
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val raw = ctx.get<String>("value").uppercase()
                    val cond = runCatching { CustomRequestCondition.valueOf(raw) }.getOrNull()
                    if (cond == null) {
                        msg(admin, "admin.settings.custom_condition_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "custom_requests.request_condition", cond.name)
                    msg(admin, "admin.settings.custom_condition_set", mapOf("value" to cond.name))
                }
        )

        // Chat prompts limits
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("chat_prompts")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("field", stringParser(), chatPromptKeySuggestions)
                .required("value", integerParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val field = ctx.get<String>("field").lowercase()
                    val value = ctx.get<Int>("value").coerceIn(1, 500)
                    val path = when (field) {
                        "bio" -> "ux.chat_prompts.max_length.bio"
                        "title" -> "ux.chat_prompts.max_length.title"
                        "description" -> "ux.chat_prompts.max_length.description"
                        else -> null
                    }
                    if (path == null) {
                        msg(admin, "admin.settings.chat_prompts_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, path, value)
                    msg(admin, "admin.settings.chat_prompts_set", mapOf("field" to field, "value" to value.toString()))
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
                        msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "term.bonus_term.enabled", value)
                    msg(admin, "admin.settings.bonus_enabled_set", mapOf("value" to value.toString()))
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
                    plugin.adminActions.updateSettingsConfig(admin, "term.bonus_term.every_x_terms", value)
                    msg(admin, "admin.settings.bonus_every_set", mapOf("value" to value.toString()))
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
                    plugin.adminActions.updateSettingsConfig(admin, "term.bonus_term.perks_per_bonus_term", value)
                    msg(admin, "admin.settings.bonus_perks_set", mapOf("value" to value.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("stepdown_enabled")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val value = parseBool(ctx.get<String>("value")) ?: run {
                        msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "election.stepdown.enabled", value)
                    msg(admin, "admin.settings.stepdown_enabled_set", mapOf("value" to value.toString()))
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
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val value = parseBool(ctx.get<String>("value")) ?: run {
                        msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "election.stepdown.allow_reapply", value)
                    msg(admin, "admin.settings.stepdown_reapply_set", mapOf("value" to value.toString()))
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("sell_all_stack")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", stringParser())
                .handler { ctx ->
                    val admin = ctx.sender().source()
                    val value = parseBool(ctx.get<String>("value")) ?: run {
                        msg(admin, "admin.settings.value_bool_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "sell_bonus.all_bonus_stack", value)
                    msg(admin, "admin.settings.sell_all_stack_set", mapOf("value" to value.toString()))
                }
        )

        // Reload
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("settings")
                .literal("reload")
                .permission(Perms.ADMIN_SETTINGS_RELOAD)
                .handler { ctx ->
                    val sender = ctx.sender().source()
                    plugin.adminActions.reload(sender as? Player)
                    msg(sender, "admin.settings.reloaded")
                }
        )

        // Audit / Health (open menus)
        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("audit")
                .permission(Perms.ADMIN_AUDIT_VIEW)
                .handler { ctx ->
                    val sender = ctx.sender().source()
                    withPlayer(sender) { admin ->
                        plugin.gui.open(admin, AdminAuditMenu(plugin))
                    }
                }
        )

        cm.command(
            cm.commandBuilder("mayor")
                .literal("admin")
                .literal("health")
                .permission(Perms.ADMIN_HEALTH_VIEW)
                .handler { ctx ->
                    val sender = ctx.sender().source()
                    withPlayer(sender) { admin ->
                        plugin.gui.open(admin, AdminHealthMenu(plugin))
                    }
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
        // Legacy aliases removed; keep canonical command paths only.
    }

    private fun msg(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        plugin.messages.msg(sender, key, placeholders)
    }

    private inline fun withPlayer(sender: CommandSender, block: (Player) -> Unit) {
        val player = sender as? Player
        if (player == null) {
            msg(sender, "errors.player_only")
            return
        }
        block(player)
    }

    private inline fun withPublicAccess(player: Player, block: () -> Unit) {
        if (!canUsePublicFeatures(player)) {
            msg(player, "public.closed")
            return
        }
        block()
    }

    private fun registerMenuRoute(
        literals: List<String>,
        permission: Permission,
        menuFactory: (Player) -> Menu,
        requirePublicAccess: Boolean = false,
        cooldownKey: String? = null,
        cooldown: Duration? = null
    ) {
        var builder = cm.commandBuilder("mayor")
        for (literal in literals) {
            builder = builder.literal(literal)
        }
        cm.command(
            builder
                .permission(permission)
                .handler { ctx ->
                    val sender = ctx.sender().source()
                    val player = sender as? Player
                    if (player == null) {
                        msg(sender, "errors.player_only")
                        return@handler
                    }
                    if (requirePublicAccess && !canUsePublicFeatures(player)) {
                        msg(player, "public.closed")
                        return@handler
                    }
                    if (cooldownKey != null && cooldown != null && checkCooldown(player, cooldownKey, cooldown)) {
                        return@handler
                    }
                    plugin.gui.open(player, menuFactory(player))
                }
        )
    }

    private fun checkCooldown(player: Player, key: String, duration: Duration): Boolean {
        val now = System.currentTimeMillis()
        val bucket = cooldowns.getOrPut(key) { mutableMapOf() }
        val last = bucket[player.uniqueId]
        if (last != null) {
            val remaining = duration.toMillis() - (now - last)
            if (remaining > 0) {
                val seconds = (remaining / 1000.0).coerceAtLeast(0.1)
                msg(player, "cooldown.wait", mapOf("seconds" to String.format(java.util.Locale.US, "%.1f", seconds)))
                return true
            }
        }
        bucket[player.uniqueId] = now
        return false
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
            msg(admin, "admin.system.master_off")
            return
        }

        val current = plugin.settings.publicEnabled
        val next = !current
        plugin.adminActions.updateSettingsConfig(admin, "public_enabled", next)

        if (next) {
            msg(admin, "admin.system.public_enabled")
        } else {
            msg(admin, "admin.system.public_disabled")
        }
    }

    private fun handleApply(player: Player) {
        val s = plugin.settings
        if (!s.enabled) {
            msg(player, "public.disabled")
            return
        }

        val now = Instant.now()
        val electionTerm = plugin.termService.computeCached(now).second
        if (!isElectionOpen(now, electionTerm)) {
            msg(player, "public.apply_closed")
            return
        }

        val playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE)
        val minTicks = s.applyPlaytimeMinutes * 60 * 20
        if (playTicks < minTicks) {
            msg(player, "public.apply_playtime", mapOf("minutes" to s.applyPlaytimeMinutes.toString()))
            return
        }

        val existing = plugin.store.candidateEntry(electionTerm, player.uniqueId)
        if (existing != null) {
            if (existing.status == CandidateStatus.REMOVED) {
                val canReapply = plugin.settings.stepdownAllowReapply &&
                    plugin.store.candidateSteppedDown(electionTerm, player.uniqueId)
                if (!canReapply) {
                    msg(player, "public.stepdown_reapply_disabled")
                    plugin.gui.open(player, CandidateMenu(plugin))
                    return
                }
            } else {
                msg(player, "public.apply_already", mapOf("term" to (electionTerm + 1).toString()))
                plugin.gui.open(player, CandidateMenu(plugin))
                return
            }
        }

        plugin.applyFlow.start(player, electionTerm)
        plugin.gui.open(player, ApplySectionsMenu(plugin))
    }

    private fun handleVote(player: Player, candidateName: String) {
        val now = Instant.now()
        val electionTerm = plugin.termService.computeCached(now).second

        if (!isElectionOpen(now, electionTerm)) {
            msg(player, "public.vote_closed")
            return
        }

        val allowChange = plugin.settings.allowVoteChange
        if (plugin.store.hasVoted(electionTerm, player.uniqueId) && !allowChange) {
            msg(player, "public.vote_already")
            return
        }

        val candidate = plugin.store.candidates(electionTerm, includeRemoved = false)
            .firstOrNull { it.lastKnownName.equals(candidateName, ignoreCase = true) }

        if (candidate == null) {
            msg(player, "public.candidate_not_found", mapOf("name" to candidateName))
            val available = plugin.store.candidates(electionTerm, includeRemoved = false)
                .filter { it.status == CandidateStatus.ACTIVE }
                .map { it.lastKnownName }
            if (available.isNotEmpty()) {
                msg(player, "public.candidates_available", mapOf("names" to available.joinToString(", ")))
            }
            return
        }

        if (candidate.status != CandidateStatus.ACTIVE) {
            msg(player, "public.candidate_in_process")
            return
        }

        plugin.gui.open(player, VoteConfirmMenu(plugin, electionTerm, candidate.uuid))
    }

    private fun handleStepDown(player: Player) {
        val now = Instant.now()
        val electionTerm = plugin.termService.computeCached(now).second

        if (!plugin.settings.stepdownEnabled) {
            msg(player, "public.stepdown_disabled")
            return
        }

        if (!isElectionOpen(now, electionTerm)) {
            msg(player, "public.stepdown_closed")
            return
        }

        val entry = plugin.store.candidateEntry(electionTerm, player.uniqueId)
        if (entry == null || entry.status == CandidateStatus.REMOVED) {
            msg(player, "public.stepdown_not_candidate")
            return
        }

        plugin.gui.open(player, StepDownConfirmMenu(plugin, electionTerm, player.uniqueId))
    }

    private fun adminSetCandidateStatus(admin: Player, name: String, status: CandidateStatus) {
        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second
        val entry = plugin.adminActions.findCandidateByName(term, name)

        if (entry == null) {
            msg(admin, "admin.candidate.not_found", mapOf("name" to name))
            val names = plugin.store.candidates(term, includeRemoved = true).map { it.lastKnownName }
            if (names.isNotEmpty()) {
                msg(admin, "admin.candidate.list", mapOf("names" to names.joinToString(", ")))
            }
            return
        }

        plugin.adminActions.setCandidateStatus(admin, term, entry.uuid, status)
        msg(
            admin,
            "admin.candidate.status_set",
            mapOf(
                "name" to entry.lastKnownName,
                "status" to status.name,
                "term" to (term + 1).toString()
            )
        )
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
            msg(to, "admin.online_players.none")
            return
        }

        val shown = names.take(25)
        val suffix = if (names.size > shown.size) " ... (+${names.size - shown.size})" else ""
        msg(to, "admin.online_players.list", mapOf("names" to shown.joinToString(", "), "suffix" to suffix))
    }
}
