package mayorSystem.data

import mayorSystem.MayorPlugin
import mayorSystem.config.TiePolicy
import mayorSystem.data.store.MysqlMayorStore
import mayorSystem.data.store.SqliteMayorStore
import mayorSystem.data.store.StoreBackend
import mayorSystem.data.store.WarmupStore
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.OffsetDateTime
import java.util.UUID
import java.util.logging.Level

class MayorStore(private val plugin: MayorPlugin) {

    private val rawBackend: StoreBackend = selectBackend()
    private val backend: StoreBackend
        get() {
            ensureReady()
            return rawBackend
        }

    private val ready = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private val loadMutex = Mutex()

    val backendId: String = rawBackend.id

    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        ready.set(false)
        runBlocking {
            loadMutex.withLock {
                rawBackend.shutdown()
            }
        }
    }

    fun isReady(): Boolean = ready.get()

    suspend fun loadAsync(): Boolean {
        if (ready.get() && !shuttingDown.get()) return true
        if (shuttingDown.get()) return false
        return loadMutex.withLock {
            if (shuttingDown.get()) return@withLock false
            if (ready.get()) return@withLock true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    (rawBackend as? WarmupStore)?.load()
                    true
                }.getOrElse { t ->
                    plugin.logger.log(
                        Level.SEVERE,
                        "[MayorStore] Load failed for configured ${rawBackend.id} store. MayorSystem will not start.",
                        t
                    )
                    false
                }
            }
            val usable = ok && !shuttingDown.get()
            ready.set(usable)
            usable
        }
    }

    private fun ensureReady() {
        if (ready.get() && !shuttingDown.get()) return
        val state = if (shuttingDown.get()) "shutting down" else "loading"
        throw IllegalStateException("MayorStore not ready (backend=$backendId, state=$state).")
    }

    fun hasEverBeenMayor(uuid: UUID): Boolean = backend.hasEverBeenMayor(uuid)

    fun winner(termIndex: Int): UUID? = backend.winner(termIndex)
    fun winnerName(termIndex: Int): String? = backend.winnerName(termIndex)
    fun highestWinnerTermOrNull(): Int? = backend.highestWinnerTermOrNull()
    fun setWinner(termIndex: Int, uuid: UUID, lastKnownName: String) =
        backend.setWinner(termIndex, uuid, lastKnownName)
    fun clearWinner(termIndex: Int) = backend.clearWinner(termIndex)

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

    fun realVoteCounts(termIndex: Int): Map<UUID, Int> =
        backend.realVoteCounts(termIndex)

    fun fakeVoteAdjustments(termIndex: Int): Map<UUID, Int> =
        backend.fakeVoteAdjustments(termIndex)

    fun fakeVoteAdjustment(termIndex: Int, candidate: UUID): Int =
        backend.fakeVoteAdjustment(termIndex, candidate)

    fun setFakeVoteAdjustment(termIndex: Int, candidate: UUID, amount: Int) =
        backend.setFakeVoteAdjustment(termIndex, candidate, amount)

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

    fun addRequestIfUnderLimit(termIndex: Int, candidate: UUID, title: String, description: String, limit: Int): Int? =
        backend.addRequestIfUnderLimit(termIndex, candidate, title, description, limit)

    fun listRequests(termIndex: Int, status: RequestStatus? = null): List<CustomPerkRequest> =
        backend.listRequests(termIndex, status)

    fun requestById(termIndex: Int, requestId: Int): CustomPerkRequest? =
        backend.requestById(termIndex, requestId)

    fun listRequestsForCandidate(termIndex: Int, candidate: UUID, status: RequestStatus? = null): List<CustomPerkRequest> =
        backend.listRequestsForCandidate(termIndex, candidate, status)

    fun setRequestStatus(termIndex: Int, requestId: Int, status: RequestStatus) =
        backend.setRequestStatus(termIndex, requestId, status)

    fun setRequestCommands(termIndex: Int, requestId: Int, onStart: List<String>, onEnd: List<String>) =
        backend.setRequestCommands(termIndex, requestId, onStart, onEnd)

    fun requestCountForCandidate(term: Int, candidate: UUID): Int =
        backend.requestCountForCandidate(term, candidate)

    fun removeRequests(termIndex: Int, requestIds: Set<Int>) =
        backend.removeRequests(termIndex, requestIds)

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

    fun resetTermData() =
        backend.resetTermData()

    private fun selectBackend(): StoreBackend {
        val type = plugin.config.getString("data.store.type", "sqlite")?.lowercase() ?: "sqlite"
        return when (type) {
            "mysql" -> createConfiguredBackend("MySQL") { MysqlMayorStore(plugin) }
            "sqlite" -> createConfiguredBackend("SQLite") { SqliteMayorStore(plugin) }
            else -> failInvalidBackendType(type)
        }
    }

    private fun createConfiguredBackend(name: String, create: () -> StoreBackend): StoreBackend =
        runCatching { create() }.getOrElse { failure ->
            val startupFailure = IllegalStateException(
                "Failed to initialize configured $name store. MayorSystem will not start.",
                failure
            )
            plugin.logger.log(
                Level.SEVERE,
                "[MayorStore] Failed to initialize configured $name store: ${failure.message}. " +
                    "MayorSystem will not start; fix data.store configuration or the database service.",
                startupFailure
            )
            throw startupFailure
        }

    private fun failInvalidBackendType(type: String): StoreBackend {
        val failure = IllegalStateException(
            "Invalid data.store.type '$type'. Expected 'sqlite' or 'mysql'. MayorSystem will not start."
        )
        plugin.logger.log(Level.SEVERE, "[MayorStore] ${failure.message}", failure)
        throw failure
    }
}

