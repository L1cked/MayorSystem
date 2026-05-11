package mayorSystem.application.usecase

import mayorSystem.MayorPlugin
import mayorSystem.config.MayorStepdownPolicy
import mayorSystem.data.CandidateStatus
import mayorSystem.ui.menus.MayorStepDownConfirmMenu
import mayorSystem.ui.menus.StepDownConfirmMenu
import org.bukkit.entity.Player
import java.time.Instant

class StepDownCommandUseCase(
    private val plugin: MayorPlugin,
    private val feedback: PlayerElectionCommandFeedback
) {
    fun stepDown(player: Player) {
        if (feedback.blockIfActionsPaused(player)) return
        val now = Instant.now()
        val (currentTerm, electionTerm) = plugin.termService.computeCached(now)
        val electionOpen = plugin.voteAccess.isElectionOpen(now, electionTerm)

        if (electionOpen) {
            if (plugin.settings.mayorStepdownPolicy == MayorStepdownPolicy.OFF) {
                feedback.msg(player, "public.stepdown_disabled")
                return
            }

            val entry = plugin.store.candidateEntry(electionTerm, player.uniqueId)
            if (entry == null || entry.status == CandidateStatus.REMOVED) {
                feedback.msg(player, "public.stepdown_not_candidate")
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
            feedback.msg(player, "public.mayor_stepdown_disabled")
            return
        }

        feedback.msg(player, "public.stepdown_closed")
    }
}
