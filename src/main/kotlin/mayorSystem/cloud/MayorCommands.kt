package mayorSystem.cloud

import mayorSystem.MayorPlugin
import mayorSystem.candidates.CandidatesCommands
import mayorSystem.config.MayorStepdownPolicy
import mayorSystem.data.CandidateStatus
import mayorSystem.economy.EconomyCommands
import mayorSystem.elections.ElectionsCommands
import mayorSystem.governance.GovernanceCommands
import mayorSystem.messaging.MessagingCommands
import mayorSystem.monitoring.MonitoringCommands
import mayorSystem.perks.PerksCommands
import mayorSystem.security.Perms
import mayorSystem.system.SystemCommands
import mayorSystem.maintenance.MaintenanceCommands
import mayorSystem.ui.menus.ApplySectionsMenu
import mayorSystem.ui.menus.CandidateMenu
import mayorSystem.ui.menus.MainMenu
import mayorSystem.ui.menus.MayorStepDownConfirmMenu
import mayorSystem.ui.menus.StatusMenu
import mayorSystem.ui.menus.StepDownConfirmMenu
import mayorSystem.ui.menus.VoteConfirmMenu
import mayorSystem.ui.menus.VoteMenu
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.incendo.cloud.paper.PaperCommandManager
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.paper.util.sender.Source
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider
import java.time.Duration
import java.time.Instant

class MayorCommands(
    private val plugin: MayorPlugin,
    private val cm: PaperCommandManager<Source>
) {
    private val ctx = CommandContext(plugin, cm)

    fun register() {
        registerPublic()
        registerAdmin()
        registerLegacyAliases()
    }

    private val voteCooldown = Duration.ofSeconds(2)
    private val applyCooldown = Duration.ofSeconds(2)

    private val candidateSuggestions = SuggestionProvider.blockingStrings<Source> { _, _ ->
        if (!plugin.isReady()) return@blockingStrings emptyList()
        val term = plugin.termService.computeNow().second
        plugin.store.candidates(term, includeRemoved = false)
            .filter { it.status == CandidateStatus.ACTIVE }
            .map { it.lastKnownName }
    }

    // ---------------------------------------------------------------------
    // Public commands
    // ---------------------------------------------------------------------

    private fun registerPublic() {
        ctx.registerMenuRoute(
            literals = emptyList(),
            permission = Permission.of(Perms.USE),
            menuFactory = { MainMenu(plugin) },
            requirePublicAccess = true
        )

        ctx.registerMenuRoute(
            literals = listOf("status"),
            permission = Permission.of(Perms.USE),
            menuFactory = { StatusMenu(plugin) },
            requirePublicAccess = true
        )

        // /mayor apply
        cm.command(
            cm.commandBuilder("mayor")
                .literal("apply")
                .permission(Perms.APPLY)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val player: Player = command.sender().source()
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
                .handler { command ->
                    val player: Player = command.sender().source()
                    withPublicAccess(player) {
                        if (checkCooldown(player, "vote", voteCooldown)) return@withPublicAccess
                        val name = command.get<String>("candidate")
                        handleVote(player, name)
                    }
                }
        )

        // /mayor vote -> open vote menu
        cm.command(
            cm.commandBuilder("mayor")
                .literal("vote")
                .permission(Permission.of(Perms.VOTE))
                .handler { command ->
                    val sender = command.sender().source()
                    val player = sender as? Player
                    if (player == null) {
                        ctx.msg(sender, "errors.player_only")
                        return@handler
                    }
                    if (!ctx.checkPublicAccess(player)) return@handler
                    if (ctx.checkCooldown(player, "vote", voteCooldown)) return@handler
                    if (ctx.blockIfActionsPaused(player)) return@handler

                    val now = Instant.now()
                    val electionTerm = plugin.termService.computeCached(now).second
                    if (!isElectionOpen(now, electionTerm)) {
                        ctx.msg(player, "public.vote_closed")
                        return@handler
                    }

                    plugin.gui.open(player, VoteMenu(plugin))
                }
        )

        // /mayor candidate
        ctx.registerMenuRoute(
            literals = listOf("candidate"),
            permission = Permission.of(Perms.CANDIDATE),
            menuFactory = { CandidateMenu(plugin) },
            requirePublicAccess = true
        )

        // /mayor stepdown
        cm.command(
            cm.commandBuilder("mayor")
                .literal("stepdown")
                .permission(Perms.CANDIDATE)
                .senderType(PlayerSource::class.java)
                .handler { command ->
                    val player: Player = command.sender().source()
                    withPublicAccess(player) {
                        handleStepDown(player)
                    }
                }
        )
    }

    // ---------------------------------------------------------------------
    // Admin commands
    // ---------------------------------------------------------------------

    private fun registerAdmin() {
        SystemCommands(ctx).register()
        GovernanceCommands(ctx).register()
        ElectionsCommands(ctx).register()
        CandidatesCommands(ctx).register()
        PerksCommands(ctx).register()
        EconomyCommands(ctx).register()
        MessagingCommands(ctx).register()
        MonitoringCommands(ctx).register()
        MaintenanceCommands(ctx).register()
    }

    private fun registerLegacyAliases() {
        // Legacy aliases removed; keep canonical command paths only.
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------
    private fun checkCooldown(player: Player, key: String, duration: Duration): Boolean =
        ctx.checkCooldown(player, key, duration)

    private inline fun withPublicAccess(player: Player, block: () -> Unit) {
        if (!ctx.checkPublicAccess(player)) return
        block()
    }

    private fun handleApply(player: Player) {
        if (ctx.blockIfActionsPaused(player)) return
        val s = plugin.settings

        val now = Instant.now()
        val electionTerm = plugin.termService.computeCached(now).second
        if (!isElectionOpen(now, electionTerm)) {
            ctx.msg(player, "public.apply_closed")
            return
        }

        val playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE)
        val minTicks = s.applyPlaytimeMinutes * 60 * 20
        if (playTicks < minTicks) {
            ctx.msg(player, "public.apply_playtime", mapOf("minutes" to s.applyPlaytimeMinutes.toString()))
            return
        }

        val existing = plugin.store.candidateEntry(electionTerm, player.uniqueId)
        if (existing != null) {
            if (existing.status == CandidateStatus.REMOVED) {
                val canReapply = plugin.settings.stepdownAllowReapply &&
                    plugin.store.candidateSteppedDown(electionTerm, player.uniqueId)
                if (!canReapply) {
                    ctx.msg(player, "public.stepdown_reapply_disabled")
                    plugin.gui.open(player, CandidateMenu(plugin))
                    return
                }
            } else {
                ctx.msg(player, "public.apply_already", mapOf("term" to (electionTerm + 1).toString()))
                plugin.gui.open(player, CandidateMenu(plugin))
                return
            }
        }

        plugin.applyFlow.start(player, electionTerm)
        plugin.gui.open(player, ApplySectionsMenu(plugin))
    }

    private fun handleVote(player: Player, candidateName: String) {
        if (ctx.blockIfActionsPaused(player)) return
        val now = Instant.now()
        val electionTerm = plugin.termService.computeCached(now).second

        if (!isElectionOpen(now, electionTerm)) {
            ctx.msg(player, "public.vote_closed")
            return
        }

        val allowChange = plugin.settings.allowVoteChange
        if (plugin.store.hasVoted(electionTerm, player.uniqueId) && !allowChange) {
            ctx.msg(player, "public.vote_already")
            return
        }

        val candidate = plugin.store.candidates(electionTerm, includeRemoved = false)
            .firstOrNull { it.lastKnownName.equals(candidateName, ignoreCase = true) }

        if (candidate == null) {
            ctx.msg(player, "public.candidate_not_found", mapOf("name" to candidateName))
            val available = plugin.store.candidates(electionTerm, includeRemoved = false)
                .filter { it.status == CandidateStatus.ACTIVE }
                .map { it.lastKnownName }
            if (available.isNotEmpty()) {
                ctx.msg(player, "public.candidates_available", mapOf("names" to available.joinToString(", ")))
            }
            return
        }

        if (candidate.status != CandidateStatus.ACTIVE) {
            ctx.msg(player, "public.candidate_in_process")
            return
        }

        plugin.gui.open(player, VoteConfirmMenu(plugin, electionTerm, candidate.uuid))
    }

    private fun handleStepDown(player: Player) {
        if (ctx.blockIfActionsPaused(player)) return
        val now = Instant.now()
        val (currentTerm, electionTerm) = plugin.termService.computeCached(now)
        val electionOpen = isElectionOpen(now, electionTerm)

        if (electionOpen) {
            if (plugin.settings.mayorStepdownPolicy == MayorStepdownPolicy.OFF) {
                ctx.msg(player, "public.stepdown_disabled")
                return
            }

            val entry = plugin.store.candidateEntry(electionTerm, player.uniqueId)
            if (entry == null || entry.status == CandidateStatus.REMOVED) {
                ctx.msg(player, "public.stepdown_not_candidate")
                return
            }

            plugin.gui.open(player, StepDownConfirmMenu(plugin, electionTerm, player.uniqueId))
            return
        }

        val policy = plugin.settings.mayorStepdownPolicy
        val currentMayor = if (currentTerm >= 0) plugin.store.winner(currentTerm) else null
        if (currentMayor != null && currentMayor == player.uniqueId && policy != MayorStepdownPolicy.OFF) {
            plugin.gui.open(player, MayorStepDownConfirmMenu(plugin, currentTerm, policy))
            return
        }

        if (currentMayor != null && currentMayor == player.uniqueId) {
            ctx.msg(player, "public.mayor_stepdown_disabled")
            return
        }

        ctx.msg(player, "public.stepdown_closed")
    }

    private fun isElectionOpen(now: Instant, term: Int): Boolean {
        return when (plugin.config.getString("admin.election_override.$term")?.uppercase()) {
            "OPEN" -> true
            "CLOSED" -> false
            else -> plugin.termService.isElectionOpen(now, term)
        }
    }
}

