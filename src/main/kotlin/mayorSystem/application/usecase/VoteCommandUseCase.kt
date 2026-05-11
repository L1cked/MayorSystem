package mayorSystem.application.usecase

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateStatus
import mayorSystem.ui.menus.VoteConfirmMenu
import mayorSystem.ui.menus.VoteMenu
import org.bukkit.entity.Player
import java.time.Instant

class VoteCommandUseCase(
    private val plugin: MayorPlugin,
    private val feedback: PlayerElectionCommandFeedback
) {
    fun voteForCandidate(player: Player, candidateName: String) {
        if (feedback.blockIfActionsPaused(player)) return
        val now = Instant.now()
        val electionTerm = plugin.voteAccess.currentElectionTerm(now)
        val denial = plugin.voteAccess.voteAccessDenial(electionTerm, player.uniqueId, now)
        if (denial != null) {
            feedback.msg(player, denial.messageKey, denial.placeholders)
            return
        }

        val candidate = plugin.voteAccess.findCandidateByName(electionTerm, candidateName)

        if (candidate == null) {
            feedback.msg(player, "public.candidate_not_found", mapOf("name" to candidateName))
            val available = plugin.voteAccess.availableCandidateNames(electionTerm)
            if (available.isNotEmpty()) {
                feedback.msg(player, "public.candidates_available", mapOf("names" to available.joinToString(", ")))
            }
            return
        }

        if (candidate.status != CandidateStatus.ACTIVE) {
            feedback.msg(player, "public.candidate_in_process")
            return
        }

        plugin.gui.open(player, VoteConfirmMenu(plugin, electionTerm, candidate.uuid))
    }

    fun openVoteMenu(player: Player) {
        if (feedback.blockIfActionsPaused(player)) return

        val now = Instant.now()
        val electionTerm = plugin.voteAccess.currentElectionTerm(now)
        val denial = plugin.voteAccess.voteAccessDenial(electionTerm, player.uniqueId, now)
        if (denial != null) {
            feedback.msg(player, denial.messageKey, denial.placeholders)
            return
        }

        plugin.gui.open(player, VoteMenu(plugin))
    }
}
