package mayorSystem.domain.election

import mayorSystem.data.ApplyBan
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

data class CandidateEligibilityInput(
    val candidateId: UUID,
    val existingCandidate: CandidateEntry?,
    val applyBan: ApplyBan?,
    val candidateSteppedDown: Boolean,
    val stepdownAllowReapply: Boolean,
    val playTicks: Int,
    val requiredPlaytimeMinutes: Int,
    val now: OffsetDateTime = OffsetDateTime.now()
)

sealed class CandidateEligibilityResult {
    data object Allowed : CandidateEligibilityResult()
    data object AlreadyCandidate : CandidateEligibilityResult()
    data object RemovedCandidate : CandidateEligibilityResult()
    data object PermanentlyBanned : CandidateEligibilityResult()
    data object MalformedApplyBan : CandidateEligibilityResult()
    data class TemporarilyBanned(val remainingMinutes: Long) : CandidateEligibilityResult()
    data class InsufficientPlaytime(val requiredMinutes: Int) : CandidateEligibilityResult()
}

class CandidateEligibilityPolicy {
    fun evaluate(input: CandidateEligibilityInput): CandidateEligibilityResult {
        input.applyBan?.let { ban ->
            if (ban.permanent) return CandidateEligibilityResult.PermanentlyBanned
            val until = ban.until ?: return CandidateEligibilityResult.MalformedApplyBan
            val remaining = Duration.between(input.now, until).toMinutes().coerceAtLeast(0)
            return CandidateEligibilityResult.TemporarilyBanned(remaining)
        }

        val minTicks = input.requiredPlaytimeMinutes.toLong() * 60L * 20L
        if (input.playTicks.toLong() < minTicks) {
            return CandidateEligibilityResult.InsufficientPlaytime(input.requiredPlaytimeMinutes)
        }

        val existing = input.existingCandidate
        if (existing != null) {
            if (existing.status == CandidateStatus.REMOVED) {
                val canReapply = input.stepdownAllowReapply && input.candidateSteppedDown
                if (!canReapply) return CandidateEligibilityResult.RemovedCandidate
            } else {
                return CandidateEligibilityResult.AlreadyCandidate
            }
        }

        return CandidateEligibilityResult.Allowed
    }
}

data class VotePolicyInput(
    val electionOpen: Boolean,
    val hasVoted: Boolean,
    val allowVoteChange: Boolean,
    val candidate: CandidateEntry?
)

sealed class VotePolicyResult {
    data object Allowed : VotePolicyResult()
    data object ElectionClosed : VotePolicyResult()
    data object AlreadyVoted : VotePolicyResult()
    data object CandidateUnavailable : VotePolicyResult()
}

class VotePolicy {
    fun evaluate(input: VotePolicyInput): VotePolicyResult {
        if (!input.electionOpen) return VotePolicyResult.ElectionClosed
        if (input.hasVoted && !input.allowVoteChange) return VotePolicyResult.AlreadyVoted
        if (input.candidate?.status != CandidateStatus.ACTIVE) return VotePolicyResult.CandidateUnavailable
        return VotePolicyResult.Allowed
    }
}
