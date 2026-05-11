package mayorSystem.domain.election

import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import mayorSystem.data.ApplyBan
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus

class ElectionPoliciesTest {
    private val candidateId = UUID.fromString("00000000-0000-0000-0000-000000000101")
    private val now = OffsetDateTime.parse("2026-01-01T00:00:00Z")

    @Test
    fun `candidate eligibility denies apply bans before playtime or candidate state`() {
        val result = CandidateEligibilityPolicy().evaluate(
            candidateInput(
                applyBan = ApplyBan(candidateId, "Alice", permanent = true, until = null, createdAt = now)
            )
        )

        assertEquals(CandidateEligibilityResult.PermanentlyBanned, result)
    }

    @Test
    fun `candidate eligibility returns remaining minutes for temporary ban`() {
        val result = CandidateEligibilityPolicy().evaluate(
            candidateInput(
                applyBan = ApplyBan(
                    candidateId,
                    "Alice",
                    permanent = false,
                    until = now.plusMinutes(90),
                    createdAt = now
                )
            )
        )

        assertEquals(CandidateEligibilityResult.TemporarilyBanned(90), result)
    }

    @Test
    fun `candidate eligibility rejects malformed temporary ban without throwing`() {
        val result = CandidateEligibilityPolicy().evaluate(
            candidateInput(
                applyBan = malformedTemporaryBan()
            )
        )

        assertEquals(CandidateEligibilityResult.MalformedApplyBan, result)
    }

    @Test
    fun `candidate eligibility handles removed candidate reapply rule`() {
        val removed = CandidateEntry(candidateId, "Alice", CandidateStatus.REMOVED)

        assertEquals(
            CandidateEligibilityResult.RemovedCandidate,
            CandidateEligibilityPolicy().evaluate(candidateInput(existingCandidate = removed))
        )
        assertEquals(
            CandidateEligibilityResult.Allowed,
            CandidateEligibilityPolicy().evaluate(
                candidateInput(
                    existingCandidate = removed,
                    candidateSteppedDown = true,
                    stepdownAllowReapply = true
                )
            )
        )
    }

    @Test
    fun `vote policy denies closed election repeated vote and unavailable candidate`() {
        val active = CandidateEntry(candidateId, "Alice", CandidateStatus.ACTIVE)
        val process = CandidateEntry(candidateId, "Alice", CandidateStatus.PROCESS)
        val policy = VotePolicy()

        assertEquals(VotePolicyResult.ElectionClosed, policy.evaluate(VotePolicyInput(false, false, true, active)))
        assertEquals(VotePolicyResult.AlreadyVoted, policy.evaluate(VotePolicyInput(true, true, false, active)))
        assertEquals(VotePolicyResult.CandidateUnavailable, policy.evaluate(VotePolicyInput(true, false, true, process)))
        assertEquals(VotePolicyResult.Allowed, policy.evaluate(VotePolicyInput(true, true, true, active)))
    }

    private fun candidateInput(
        existingCandidate: CandidateEntry? = null,
        applyBan: ApplyBan? = null,
        candidateSteppedDown: Boolean = false,
        stepdownAllowReapply: Boolean = false
    ): CandidateEligibilityInput =
        CandidateEligibilityInput(
            candidateId = candidateId,
            existingCandidate = existingCandidate,
            applyBan = applyBan,
            candidateSteppedDown = candidateSteppedDown,
            stepdownAllowReapply = stepdownAllowReapply,
            playTicks = 20 * 60 * 10,
            requiredPlaytimeMinutes = 10,
            now = now
        )

    private fun malformedTemporaryBan(): ApplyBan {
        val ban = ApplyBan(candidateId, "Alice", permanent = true, until = null, createdAt = now)
        val permanent = ApplyBan::class.java.getDeclaredField("permanent")
        permanent.isAccessible = true
        permanent.setBoolean(ban, false)
        return ban
    }
}
