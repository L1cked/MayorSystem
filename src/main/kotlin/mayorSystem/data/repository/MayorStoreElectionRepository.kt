package mayorSystem.data.repository

import mayorSystem.data.ApplyBan
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.CustomPerkRequest
import mayorSystem.data.MayorStore
import mayorSystem.data.RequestStatus
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Application-facing repository adapter over the current MayorStore facade.
 *
 * MayorStore remains the compatibility surface for existing services and addons;
 * new use-cases should depend on this contract where their workflow is already
 * suspend-aware.
 */
class MayorStoreElectionRepository(
    private val store: MayorStore
) : ElectionRepository {
    override suspend fun hasEverBeenMayor(uuid: UUID): Boolean = store.hasEverBeenMayor(uuid)
    override suspend fun winner(term: Int): UUID? = store.winner(term)
    override suspend fun winnerName(term: Int): String? = store.winnerName(term)
    override suspend fun setWinner(term: Int, uuid: UUID, lastKnownName: String) = store.setWinner(term, uuid, lastKnownName)
    override suspend fun clearWinner(term: Int) = store.clearWinner(term)

    override suspend fun candidates(term: Int, includeRemoved: Boolean): List<CandidateEntry> = store.candidates(term, includeRemoved)
    override suspend fun candidateEntry(term: Int, uuid: UUID): CandidateEntry? = store.candidateEntry(term, uuid)
    override suspend fun setCandidate(term: Int, uuid: UUID, name: String) = store.setCandidate(term, uuid, name)
    override suspend fun setCandidateStatus(term: Int, uuid: UUID, status: CandidateStatus) = store.setCandidateStatus(term, uuid, status)
    override suspend fun candidateSteppedDown(term: Int, uuid: UUID): Boolean = store.candidateSteppedDown(term, uuid)
    override suspend fun setCandidateStepdown(term: Int, uuid: UUID) = store.setCandidateStepdown(term, uuid)
    override suspend fun candidateBio(term: Int, candidate: UUID): String = store.candidateBio(term, candidate)
    override suspend fun setCandidateBio(term: Int, candidate: UUID, bio: String) = store.setCandidateBio(term, candidate, bio)

    override suspend fun hasVoted(term: Int, voter: UUID): Boolean = store.hasVoted(term, voter)
    override suspend fun votedFor(term: Int, voter: UUID): UUID? = store.votedFor(term, voter)
    override suspend fun vote(term: Int, voter: UUID, candidate: UUID) = store.vote(term, voter, candidate)
    override suspend fun voteCounts(term: Int): Map<UUID, Int> = store.voteCounts(term)
    override suspend fun realVoteCounts(term: Int): Map<UUID, Int> = store.realVoteCounts(term)
    override suspend fun fakeVoteAdjustments(term: Int): Map<UUID, Int> = store.fakeVoteAdjustments(term)
    override suspend fun setFakeVoteAdjustment(term: Int, candidate: UUID, amount: Int) =
        store.setFakeVoteAdjustment(term, candidate, amount)

    override suspend fun chosenPerks(term: Int, candidate: UUID): Set<String> = store.chosenPerks(term, candidate)
    override suspend fun setChosenPerks(term: Int, candidate: UUID, perks: Set<String>) = store.setChosenPerks(term, candidate, perks)
    override suspend fun setPerksLocked(term: Int, candidate: UUID, locked: Boolean) = store.setPerksLocked(term, candidate, locked)
    override suspend fun isPerksLocked(term: Int, candidate: UUID): Boolean = store.isPerksLocked(term, candidate)

    override suspend fun activeApplyBan(uuid: UUID): ApplyBan? = store.activeApplyBan(uuid)
    override suspend fun setApplyBanPermanent(uuid: UUID, lastKnownName: String) = store.setApplyBanPermanent(uuid, lastKnownName)
    override suspend fun setApplyBanTemp(uuid: UUID, lastKnownName: String, until: OffsetDateTime) =
        store.setApplyBanTemp(uuid, lastKnownName, until)
    override suspend fun clearApplyBan(uuid: UUID) = store.clearApplyBan(uuid)
    override suspend fun listApplyBans(): List<ApplyBan> = store.listApplyBans()

    override suspend fun addRequest(term: Int, candidate: UUID, title: String, description: String): Int =
        store.addRequest(term, candidate, title, description)
    override suspend fun listRequests(term: Int, status: RequestStatus?): List<CustomPerkRequest> = store.listRequests(term, status)
    override suspend fun requestById(term: Int, requestId: Int): CustomPerkRequest? = store.requestById(term, requestId)
    override suspend fun setRequestStatus(term: Int, requestId: Int, status: RequestStatus) =
        store.setRequestStatus(term, requestId, status)
    override suspend fun setRequestCommands(term: Int, requestId: Int, onStart: List<String>, onEnd: List<String>) =
        store.setRequestCommands(term, requestId, onStart, onEnd)

    override suspend fun resetTermData() = store.resetTermData()
}
