package mayorSystem.application.usecase

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.ui.menus.ApplySectionsMenu
import mayorSystem.ui.menus.CandidateMenu
import org.bukkit.Statistic
import org.bukkit.entity.Player
import java.time.Instant

class ApplyCandidateCommandUseCase(
    private val plugin: MayorPlugin,
    private val feedback: PlayerElectionCommandFeedback
) {
    fun apply(player: Player) {
        if (feedback.blockIfActionsPaused(player)) return
        val s = plugin.settings

        val now = Instant.now()
        val electionTerm = plugin.termService.computeCached(now).second
        if (!plugin.voteAccess.isElectionOpen(now, electionTerm)) {
            feedback.msg(player, "public.apply_closed")
            return
        }

        val playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE)
        val minTicks = s.applyPlaytimeMinutes * 60 * 20
        if (playTicks < minTicks) {
            feedback.msg(player, "public.apply_playtime", mapOf("minutes" to s.applyPlaytimeMinutes.toString()))
            return
        }

        val existing = plugin.store.candidateEntry(electionTerm, player.uniqueId)
        if (existing != null) {
            if (existing.status == CandidateStatus.REMOVED) {
                val canReapply = plugin.settings.stepdownAllowReapply &&
                    plugin.store.candidateSteppedDown(electionTerm, player.uniqueId)
                if (!canReapply) {
                    feedback.msg(player, "public.stepdown_reapply_disabled")
                    plugin.gui.open(player, CandidateMenu(plugin))
                    return
                }
            } else {
                feedback.msg(player, "public.apply_already", mapOf("term" to (electionTerm + 1).toString()))
                plugin.gui.open(player, CandidateMenu(plugin))
                return
            }
        }

        plugin.applyFlow.start(player, electionTerm)
        plugin.gui.open(player, ApplySectionsMenu(plugin))
    }
}
