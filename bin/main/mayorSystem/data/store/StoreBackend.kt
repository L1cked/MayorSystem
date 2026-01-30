package mayorSystem.data.store

import mayorSystem.data.ApplyBan
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.CustomPerkRequest
import mayorSystem.data.RequestStatus
import mayorSystem.config.TiePolicy
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

interface StoreBackend {
    val id: String

    fun hasEverBeenMayor(uuid: UUID): Boolean

    fun winner(termIndex: Int): UUID?
    fun winnerName(termIndex: Int): String?
    fun setWinner(termIndex: Int, uuid: UUID, lastKnownName: String)

    fun electionOpenAnnounced(termIndex: Int): Boolean
    fun setElectionOpenAnnounced(termIndex: Int, value: Boolean)
    fun mayorElectedAnnounced(termIndex: Int): Boolean
    fun setMayorElectedAnnounced(termIndex: Int, value: Boolean)

    fun candidates(termIndex: Int, includeRemoved: Boolean): List<CandidateEntry>
    fun isCandidate(termIndex: Int, uuid: UUID): Boolean
    fun setCandidate(termIndex: Int, uuid: UUID, name: String)
    fun candidateBio(termIndex: Int, candidate: UUID): String
    fun setCandidateBio(termIndex: Int, candidate: UUID, bio: String)
    fun candidateAppliedAt(termIndex: Int, candidate: UUID): Instant?
    fun setCandidateStatus(termIndex: Int, uuid: UUID, status: CandidateStatus)
    fun setCandidateStepdown(termIndex: Int, uuid: UUID)
    fun candidateSteppedDown(termIndex: Int, uuid: UUID): Boolean
    fun isPerksLocked(termIndex: Int, candidate: UUID): Boolean
    fun setPerksLocked(termIndex: Int, candidate: UUID, locked: Boolean)

    fun hasVoted(termIndex: Int, voter: UUID): Boolean
    fun votedFor(termIndex: Int, voter: UUID): UUID?
    fun vote(termIndex: Int, voter: UUID, candidate: UUID)
    fun voteCounts(termIndex: Int): Map<UUID, Int>

    fun pickWinner(
        termIndex: Int,
        tiePolicy: TiePolicy,
        incumbent: UUID? = null,
        seededRngSeed: Long = termIndex.toLong(),
        logDecision: ((String) -> Unit)? = null
    ): CandidateEntry?

    fun topCandidates(term: Int, limit: Int = 3, includeRemoved: Boolean = false): List<Pair<CandidateEntry, Int>>

    fun chosenPerks(termIndex: Int, candidate: UUID): Set<String>
    fun setChosenPerks(termIndex: Int, candidate: UUID, perks: Set<String>)

    fun addRequest(termIndex: Int, candidate: UUID, title: String, description: String): Int
    fun listRequests(termIndex: Int, status: RequestStatus? = null): List<CustomPerkRequest>
    fun setRequestStatus(termIndex: Int, requestId: Int, status: RequestStatus)
    fun setRequestCommands(termIndex: Int, requestId: Int, onStart: List<String>, onEnd: List<String>)
    fun requestCountForCandidate(term: Int, candidate: UUID): Int
    fun clearRequests(termIndex: Int)
    fun clearUnapprovedRequests(termIndex: Int)

    fun activeApplyBan(uuid: UUID): ApplyBan?
    fun setApplyBanPermanent(uuid: UUID, lastKnownName: String)
    fun setApplyBanTemp(uuid: UUID, lastKnownName: String, until: OffsetDateTime)
    fun clearApplyBan(uuid: UUID)
    fun listApplyBans(): List<ApplyBan>

    fun candidateEntry(termIndex: Int, uuid: UUID): CandidateEntry?

    fun shutdown()
}
