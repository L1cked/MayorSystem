package mayorSystem.application.usecase

import mayorSystem.domain.election.CandidateEligibilityInput
import mayorSystem.domain.election.CandidateEligibilityResult
import mayorSystem.domain.election.VotePolicyInput
import mayorSystem.domain.election.VotePolicyResult
import mayorSystem.domain.perk.PerkSelectionInput
import mayorSystem.domain.perk.PerkSelectionResult
import java.util.UUID

data class ApplyCandidateCommand(
    val playerId: UUID,
    val playerName: String,
    val term: Int,
    val selectedPerkIds: Set<String>,
    val eligibility: CandidateEligibilityInput,
    val perkSelection: PerkSelectionInput,
    val applyCost: Double
)

sealed class ApplyCandidateResult {
    data object Applied : ApplyCandidateResult()
    data class EligibilityDenied(val reason: CandidateEligibilityResult) : ApplyCandidateResult()
    data class PerksDenied(val reason: PerkSelectionResult) : ApplyCandidateResult()
    data object EconomyUnavailable : ApplyCandidateResult()
    data object InsufficientFunds : ApplyCandidateResult()
    data object PaymentFailed : ApplyCandidateResult()
    data object CommitFailedRefunded : ApplyCandidateResult()
    data object CommitFailedRefundFailed : ApplyCandidateResult()
}

interface ApplyCandidateUseCase {
    suspend fun apply(command: ApplyCandidateCommand): ApplyCandidateResult
}

data class VoteCommand(
    val voterId: UUID,
    val candidateId: UUID,
    val term: Int,
    val policyInput: VotePolicyInput
)

sealed class VoteResult {
    data object Voted : VoteResult()
    data object ChangedVote : VoteResult()
    data class Denied(val reason: VotePolicyResult) : VoteResult()
}

interface VoteUseCase {
    suspend fun vote(command: VoteCommand): VoteResult
}

