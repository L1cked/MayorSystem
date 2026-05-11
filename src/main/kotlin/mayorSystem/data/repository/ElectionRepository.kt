package mayorSystem.data.repository

import mayorSystem.data.ApplyBan
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.CustomPerkRequest
import mayorSystem.data.RequestStatus
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Suspend-aware election storage contract for application use-cases.
 *
 * This interface preserves the current MayorStore semantics: authorization,
 * validation, serialization, and audit logging are enforced by application
 * services before writes reach the repository. Implementations may delegate to
 * synchronous stores internally, but callers should invoke them from an
 * appropriate dispatcher when doing blocking work.
 */
interface ElectionRepository {
    /** Returns true when the player has a recorded winner row in any term. */
    suspend fun hasEverBeenMayor(uuid: UUID): Boolean

    /** Returns the winner UUID for a term, or null when no mayor was recorded. */
    suspend fun winner(term: Int): UUID?

    /** Returns the last known winner name for a term, or null when missing. */
    suspend fun winnerName(term: Int): String?

    /** Stores the term winner and last known name. Existing winner data is replaced. */
    suspend fun setWinner(term: Int, uuid: UUID, lastKnownName: String)

    /** Clears the winner row for a term without changing candidate or vote rows. */
    suspend fun clearWinner(term: Int)

    /** Lists candidates for a term, optionally including removed candidates. */
    suspend fun candidates(term: Int, includeRemoved: Boolean): List<CandidateEntry>

    /** Returns one candidate entry for a term, or null when the player never applied. */
    suspend fun candidateEntry(term: Int, uuid: UUID): CandidateEntry?

    /** Creates or updates a candidate row with the player's last known name. */
    suspend fun setCandidate(term: Int, uuid: UUID, name: String)

    /** Updates the candidate lifecycle status for moderation workflows. */
    suspend fun setCandidateStatus(term: Int, uuid: UUID, status: CandidateStatus)

    /** Returns true when the candidate used the step-down flow for this term. */
    suspend fun candidateSteppedDown(term: Int, uuid: UUID): Boolean

    /** Marks a candidate as stepped down and removed for this term. */
    suspend fun setCandidateStepdown(term: Int, uuid: UUID)

    /** Returns the candidate bio, or an empty string when no bio is stored. */
    suspend fun candidateBio(term: Int, candidate: UUID): String

    /** Stores the candidate bio text for a term. */
    suspend fun setCandidateBio(term: Int, candidate: UUID, bio: String)

    /** Returns true when the voter has a ballot in the term. */
    suspend fun hasVoted(term: Int, voter: UUID): Boolean

    /** Returns the candidate UUID currently selected by the voter, or null. */
    suspend fun votedFor(term: Int, voter: UUID): UUID?

    /**
     * Records or replaces a voter's ballot for a candidate.
     *
     * Vote-change policy is enforced by VoteAccessService and UI/use-cases.
     */
    suspend fun vote(term: Int, voter: UUID, candidate: UUID)

    /** Returns total vote counts with fake adjustments applied. */
    suspend fun voteCounts(term: Int): Map<UUID, Int>

    /** Returns only real player ballot counts. */
    suspend fun realVoteCounts(term: Int): Map<UUID, Int>

    /** Returns configured fake vote adjustments by candidate UUID. */
    suspend fun fakeVoteAdjustments(term: Int): Map<UUID, Int>

    /**
     * Sets the absolute fake vote adjustment for a candidate.
     *
     * Permissions, bounds, and audit logging are enforced by admin use-cases.
     */
    suspend fun setFakeVoteAdjustment(term: Int, candidate: UUID, amount: Int)

    /** Returns selected perk ids for a candidate. */
    suspend fun chosenPerks(term: Int, candidate: UUID): Set<String>

    /** Replaces selected perk ids; lock enforcement remains in higher-level workflows. */
    suspend fun setChosenPerks(term: Int, candidate: UUID, perks: Set<String>)

    /** Sets whether the candidate's perk choices are locked. */
    suspend fun setPerksLocked(term: Int, candidate: UUID, locked: Boolean)

    /** Returns true when the candidate's perk choices are locked. */
    suspend fun isPerksLocked(term: Int, candidate: UUID): Boolean

    /** Returns the active apply ban for a player, or null when none is active. */
    suspend fun activeApplyBan(uuid: UUID): ApplyBan?

    /** Creates or replaces a permanent apply ban. */
    suspend fun setApplyBanPermanent(uuid: UUID, lastKnownName: String)

    /** Creates or replaces a temporary apply ban ending at the provided timestamp. */
    suspend fun setApplyBanTemp(uuid: UUID, lastKnownName: String, until: OffsetDateTime)

    /** Clears any apply ban for the player. */
    suspend fun clearApplyBan(uuid: UUID)

    /** Lists all stored apply bans, including expired rows retained by the backend. */
    suspend fun listApplyBans(): List<ApplyBan>

    /** Adds a custom perk request and returns its generated id. */
    suspend fun addRequest(term: Int, candidate: UUID, title: String, description: String): Int

    /** Lists custom perk requests for a term, optionally filtered by status. */
    suspend fun listRequests(term: Int, status: RequestStatus? = null): List<CustomPerkRequest>

    /** Returns a request by id for a term, or null when missing. */
    suspend fun requestById(term: Int, requestId: Int): CustomPerkRequest?

    /** Updates a custom perk request status. */
    suspend fun setRequestStatus(term: Int, requestId: Int, status: RequestStatus)

    /** Stores command lists associated with an approved custom perk request. */
    suspend fun setRequestCommands(term: Int, requestId: Int, onStart: List<String>, onEnd: List<String>)

    /**
     * Clears all election term data from the configured store.
     *
     * This is intentionally all-term because it mirrors the existing admin
     * maintenance reset and is only called through serialized, permission-gated
     * admin workflows.
     */
    suspend fun resetTermData()
}
