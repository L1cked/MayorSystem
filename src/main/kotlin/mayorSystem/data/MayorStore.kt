package mayorSystem.data

import mayorSystem.MayorPlugin
import mayorSystem.config.TiePolicy
import mayorSystem.data.store.SqliteMayorStore
import mayorSystem.data.store.StoreBackend
import mayorSystem.data.store.YamlMayorStore
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class MayorStore(private val plugin: MayorPlugin) {

    private val backend: StoreBackend = selectBackend()

    val backendId: String = backend.id

    fun shutdown() = backend.shutdown()

    fun hasEverBeenMayor(uuid: UUID): Boolean = backend.hasEverBeenMayor(uuid)

    fun winner(termIndex: Int): UUID? = backend.winner(termIndex)
    fun winnerName(termIndex: Int): String? = backend.winnerName(termIndex)
    fun setWinner(termIndex: Int, uuid: UUID, lastKnownName: String) =
        backend.setWinner(termIndex, uuid, lastKnownName)

    fun electionOpenAnnounced(termIndex: Int): Boolean =
        backend.electionOpenAnnounced(termIndex)

    fun setElectionOpenAnnounced(termIndex: Int, value: Boolean) =
        backend.setElectionOpenAnnounced(termIndex, value)

    fun mayorElectedAnnounced(termIndex: Int): Boolean =
        backend.mayorElectedAnnounced(termIndex)

    fun setMayorElectedAnnounced(termIndex: Int, value: Boolean) =
        backend.setMayorElectedAnnounced(termIndex, value)

    fun candidates(termIndex: Int, includeRemoved: Boolean): List<CandidateEntry> =
        backend.candidates(termIndex, includeRemoved)

    fun isCandidate(termIndex: Int, uuid: UUID): Boolean =
        backend.isCandidate(termIndex, uuid)

    fun setCandidate(termIndex: Int, uuid: UUID, name: String) =
        backend.setCandidate(termIndex, uuid, name)

    fun candidateBio(termIndex: Int, candidate: UUID): String =
        backend.candidateBio(termIndex, candidate)

    fun setCandidateBio(termIndex: Int, candidate: UUID, bio: String) =
        backend.setCandidateBio(termIndex, candidate, bio)

    fun candidateAppliedAt(termIndex: Int, candidate: UUID): Instant? =
        backend.candidateAppliedAt(termIndex, candidate)

    fun setCandidateStatus(termIndex: Int, uuid: UUID, status: CandidateStatus) =
        backend.setCandidateStatus(termIndex, uuid, status)

    fun setCandidateStepdown(termIndex: Int, uuid: UUID) =
        backend.setCandidateStepdown(termIndex, uuid)

    fun candidateSteppedDown(termIndex: Int, uuid: UUID): Boolean =
        backend.candidateSteppedDown(termIndex, uuid)

    fun isPerksLocked(termIndex: Int, candidate: UUID): Boolean =
        backend.isPerksLocked(termIndex, candidate)

    fun setPerksLocked(termIndex: Int, candidate: UUID, locked: Boolean) =
        backend.setPerksLocked(termIndex, candidate, locked)

    fun hasVoted(termIndex: Int, voter: UUID): Boolean =
        backend.hasVoted(termIndex, voter)

    fun votedFor(termIndex: Int, voter: UUID): UUID? =
        backend.votedFor(termIndex, voter)

    fun vote(termIndex: Int, voter: UUID, candidate: UUID) =
        backend.vote(termIndex, voter, candidate)

    fun voteCounts(termIndex: Int): Map<UUID, Int> =
        backend.voteCounts(termIndex)

    fun pickWinner(
        termIndex: Int,
        tiePolicy: TiePolicy,
        incumbent: UUID? = null,
        seededRngSeed: Long = termIndex.toLong(),
        logDecision: ((String) -> Unit)? = null
    ): CandidateEntry? = backend.pickWinner(termIndex, tiePolicy, incumbent, seededRngSeed, logDecision)

    fun topCandidates(term: Int, limit: Int = 3, includeRemoved: Boolean = false): List<Pair<CandidateEntry, Int>> =
        backend.topCandidates(term, limit, includeRemoved)

    fun chosenPerks(termIndex: Int, candidate: UUID): Set<String> =
        backend.chosenPerks(termIndex, candidate)

    fun setChosenPerks(termIndex: Int, candidate: UUID, perks: Set<String>) =
        backend.setChosenPerks(termIndex, candidate, perks)

    fun addRequest(termIndex: Int, candidate: UUID, title: String, description: String): Int =
        backend.addRequest(termIndex, candidate, title, description)

    fun listRequests(termIndex: Int, status: RequestStatus? = null): List<CustomPerkRequest> =
        backend.listRequests(termIndex, status)

    fun setRequestStatus(termIndex: Int, requestId: Int, status: RequestStatus) =
        backend.setRequestStatus(termIndex, requestId, status)

    fun setRequestCommands(termIndex: Int, requestId: Int, onStart: List<String>, onEnd: List<String>) =
        backend.setRequestCommands(termIndex, requestId, onStart, onEnd)

    fun requestCountForCandidate(term: Int, candidate: UUID): Int =
        backend.requestCountForCandidate(term, candidate)

    fun clearRequests(termIndex: Int) =
        backend.clearRequests(termIndex)

    fun clearUnapprovedRequests(termIndex: Int) =
        backend.clearUnapprovedRequests(termIndex)

    fun activeApplyBan(uuid: UUID): ApplyBan? = backend.activeApplyBan(uuid)

    fun setApplyBanPermanent(uuid: UUID, lastKnownName: String) =
        backend.setApplyBanPermanent(uuid, lastKnownName)

    fun setApplyBanTemp(uuid: UUID, lastKnownName: String, until: OffsetDateTime) =
        backend.setApplyBanTemp(uuid, lastKnownName, until)

    fun clearApplyBan(uuid: UUID) =
        backend.clearApplyBan(uuid)

    fun listApplyBans(): List<ApplyBan> =
        backend.listApplyBans()

    fun candidateEntry(termIndex: Int, uuid: UUID): CandidateEntry? =
        backend.candidateEntry(termIndex, uuid)

    private fun selectBackend(): StoreBackend {
        val type = plugin.config.getString("data.store.type", "sqlite")?.lowercase() ?: "sqlite"
        return when (type) {
            "yaml" -> runCatching { YamlMayorStore(plugin) }.getOrElse {
                plugin.logger.warning("[MayorStore] Failed to init YAML store: ${it.message}. Falling back to sqlite.")
                SqliteMayorStore(plugin)
            }
            "sqlite" -> runCatching { SqliteMayorStore(plugin) }.getOrElse {
                plugin.logger.warning("[MayorStore] Failed to init SQLite store: ${it.message}. Falling back to yaml.")
                YamlMayorStore(plugin)
            }
            else -> {
                plugin.logger.warning("[MayorStore] Unknown data.store.type '$type', falling back to sqlite.")
                runCatching { SqliteMayorStore(plugin) }.getOrElse { YamlMayorStore(plugin) }
            }
        }
    }
}
