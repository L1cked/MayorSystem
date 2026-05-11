package mayorSystem.application.usecase

import mayorSystem.MayorPlugin
import org.bukkit.entity.Player

interface PlayerElectionCommandFeedback {
    fun msg(player: Player, key: String, placeholders: Map<String, String> = emptyMap())
    fun blockIfActionsPaused(player: Player): Boolean
}

/**
 * Command-facing use-cases for player election actions.
 *
 * These preserve the existing Bukkit/UI behavior while keeping the command registrar thin.
 * Later phases can split the pure rules behind these methods into domain services.
 */
class PlayerElectionCommandUseCases(
    plugin: MayorPlugin,
    feedback: PlayerElectionCommandFeedback
) {
    private val applyCandidate = ApplyCandidateCommandUseCase(plugin, feedback)
    private val vote = VoteCommandUseCase(plugin, feedback)
    private val stepDown = StepDownCommandUseCase(plugin, feedback)

    fun apply(player: Player) = applyCandidate.apply(player)

    fun voteForCandidate(player: Player, candidateName: String) =
        vote.voteForCandidate(player, candidateName)

    fun openVoteMenu(player: Player) = vote.openVoteMenu(player)

    fun stepDown(player: Player) = stepDown.stepDown(player)
}
