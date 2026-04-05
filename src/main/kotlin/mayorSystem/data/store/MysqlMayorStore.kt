package mayorSystem.data.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import mayorSystem.MayorPlugin
import mayorSystem.config.TiePolicy
import mayorSystem.data.ApplyBan
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.CustomPerkRequest
import mayorSystem.data.RequestStatus
import java.sql.Connection
import java.sql.PreparedStatement
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.random.Random

class MysqlMayorStore(private val plugin: MayorPlugin) : StoreBackend, WarmupStore {
    override val id: String = "mysql"

    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type

    private val asyncWrites = plugin.config.getBoolean("data.store.mysql.async_writes", true)
    private val strictWrites = plugin.config.getBoolean("data.store.mysql.strict", true)
    private val host = plugin.config.getString("data.store.mysql.host", "localhost") ?: "localhost"
    private val port = plugin.config.getInt("data.store.mysql.port", 3306)
    private val database = plugin.config.getString("data.store.mysql.database", "mayorsystem") ?: "mayorsystem"
    private val username = plugin.config.getString("data.store.mysql.user", "root") ?: "root"
    private val password = plugin.config.getString("data.store.mysql.password", "") ?: ""
    private val useSsl = plugin.config.getBoolean("data.store.mysql.use_ssl", false)
    private val serverTimezone = plugin.config.getString("data.store.mysql.server_timezone", "UTC") ?: "UTC"
    private val params = plugin.config.getString("data.store.mysql.params", "") ?: ""
    private val poolSize = plugin.config.getInt("data.store.mysql.pool_size", 4).coerceAtLeast(1)
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = buildJdbcUrl()
        username = this@MysqlMayorStore.username
        password = this@MysqlMayorStore.password
        maximumPoolSize = poolSize
        connectionTimeout = 5000
        validationTimeout = 3000
        isAutoCommit = true
    })
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MayorStore-MySQL").apply { isDaemon = true }
    }
    private val stateLock = ReentrantReadWriteLock()
    private val writeLock = ReentrantLock()

    private val winners = ConcurrentHashMap<Int, UUID>()
    private val winnerNames = ConcurrentHashMap<Int, String>()
    private val termFlags = ConcurrentHashMap<Int, TermFlags>()
    private val candidatesByTerm = ConcurrentHashMap<Int, ConcurrentHashMap<UUID, CandidateRecord>>()
    private val votesByTerm = ConcurrentHashMap<Int, ConcurrentHashMap<UUID, UUID>>()
    private val voteCountsByTerm = ConcurrentHashMap<Int, ConcurrentHashMap<UUID, Int>>()
    private val fakeVotesByTerm = ConcurrentHashMap<Int, ConcurrentHashMap<UUID, Int>>()
    private val requestsByTerm = ConcurrentHashMap<Int, ConcurrentHashMap<Int, RequestRecord>>()
    private val requestNextId = ConcurrentHashMap<Int, AtomicInteger>()
    private val applyBans = ConcurrentHashMap<UUID, ApplyBan>()
    private val everMayors: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    private val initialized = AtomicBoolean(false)

    override fun load() {
        dataSource.connection.use { c ->
            initSchema(c)
            loadAll(c)
        }
        initialized.set(true)
    }

    override fun shutdown() {
        executor.shutdown()
        runCatching { executor.awaitTermination(5, TimeUnit.SECONDS) }
        runCatching { dataSource.close() }
    }

    override fun hasEverBeenMayor(uuid: UUID): Boolean = readState { everMayors.contains(uuid) }

    // ------------------------------------------------------------------------
    // Winner
    // ------------------------------------------------------------------------

    override fun winner(termIndex: Int): UUID? = readState { winners[termIndex] }

    override fun winnerName(termIndex: Int): String? = readState { winnerNames[termIndex] }

    override fun setWinner(termIndex: Int, uuid: UUID, lastKnownName: String) = writeState {
        winners[termIndex] = uuid
        winnerNames[termIndex] = lastKnownName
        everMayors.add(uuid)
        enqueueWrite { c ->
            c.prepareStatement(
                "INSERT INTO terms(term, winner_uuid, winner_name) VALUES(?,?,?) " +
                    "ON DUPLICATE KEY UPDATE winner_uuid=VALUES(winner_uuid), winner_name=VALUES(winner_name)"
            ).use {
                it.setInt(1, termIndex)
                it.setString(2, uuid.toString())
                it.setString(3, lastKnownName)
                it.executeUpdate()
            }
        }
    }

    override fun clearWinner(termIndex: Int) = writeState {
        winners.remove(termIndex)
        winnerNames.remove(termIndex)
        enqueueWrite { c ->
            c.prepareStatement(
                "UPDATE terms SET winner_uuid=NULL, winner_name=NULL WHERE term=?"
            ).use {
                it.setInt(1, termIndex)
                it.executeUpdate()
            }
        }
    }

    // ------------------------------------------------------------------------
    // Term flags
    // ------------------------------------------------------------------------

    override fun electionOpenAnnounced(termIndex: Int): Boolean = readState {
        termFlags[termIndex]?.electionOpenAnnounced ?: false
    }

    override fun setElectionOpenAnnounced(termIndex: Int, value: Boolean) = writeState {
        val flags = termFlags.computeIfAbsent(termIndex) { TermFlags() }
        flags.electionOpenAnnounced = value
        upsertFlags(termIndex, flags)
    }

    override fun mayorElectedAnnounced(termIndex: Int): Boolean = readState {
        termFlags[termIndex]?.mayorElectedAnnounced ?: false
    }

    override fun setMayorElectedAnnounced(termIndex: Int, value: Boolean) = writeState {
        val flags = termFlags.computeIfAbsent(termIndex) { TermFlags() }
        flags.mayorElectedAnnounced = value
        upsertFlags(termIndex, flags)
    }

    // ------------------------------------------------------------------------
    // Candidates
    // ------------------------------------------------------------------------

    override fun candidates(termIndex: Int, includeRemoved: Boolean): List<CandidateEntry> = readState {
        val map = candidatesByTerm[termIndex] ?: return@readState emptyList()
        map.values
            .map { CandidateEntry(it.uuid, it.name, it.status) }
            .filter { includeRemoved || it.status != CandidateStatus.REMOVED }
    }

    override fun isCandidate(termIndex: Int, uuid: UUID): Boolean = readState {
        candidatesByTerm[termIndex]?.containsKey(uuid) == true
    }

    override fun setCandidate(termIndex: Int, uuid: UUID, name: String) = writeState {
        val rec = candidatesByTerm
            .computeIfAbsent(termIndex) { ConcurrentHashMap() }
            .computeIfAbsent(uuid) {
                CandidateRecord(
                    uuid = uuid,
                    name = name,
                    status = CandidateStatus.ACTIVE,
                    appliedAt = Instant.now(),
                    bio = "",
                    perksLocked = false,
                    stepdown = false,
                    perks = mutableSetOf()
                )
            }
        rec.name = name
        rec.status = CandidateStatus.ACTIVE
        rec.stepdown = false
        if (rec.appliedAt == null) rec.appliedAt = Instant.now()
        if (rec.bio.isBlank()) rec.bio = ""
        upsertCandidate(termIndex, rec)
    }

    override fun candidateBio(termIndex: Int, candidate: UUID): String = readState {
        candidatesByTerm[termIndex]?.get(candidate)?.bio ?: ""
    }

    override fun setCandidateBio(termIndex: Int, candidate: UUID, bio: String) = writeState {
        val rec = candidatesByTerm
            .computeIfAbsent(termIndex) { ConcurrentHashMap() }
            .computeIfAbsent(candidate) {
                CandidateRecord(
                    uuid = candidate,
                    name = "Unknown",
                    status = CandidateStatus.ACTIVE,
                    appliedAt = Instant.now(),
                    bio = bio,
                    perksLocked = false,
                    stepdown = false,
                    perks = mutableSetOf()
                )
            }
        rec.bio = bio
        upsertCandidate(termIndex, rec)
    }

    override fun candidateAppliedAt(termIndex: Int, candidate: UUID): Instant? = readState {
        candidatesByTerm[termIndex]?.get(candidate)?.appliedAt
    }

    override fun setCandidateStatus(termIndex: Int, uuid: UUID, status: CandidateStatus) = writeState {
        val rec = candidatesByTerm[termIndex]?.get(uuid) ?: return@writeState
        rec.status = status
        rec.stepdown = false

        if (status == CandidateStatus.REMOVED) {
            removeVotesForCandidate(termIndex, uuid)
        }
        enqueueWrite { c ->
            runInTransaction(c) {
                upsertCandidateDb(c, termIndex, rec)
                if (status == CandidateStatus.REMOVED) {
                    c.prepareStatement("DELETE FROM votes WHERE term=? AND candidate_uuid=?").use {
                        it.setInt(1, termIndex)
                        it.setString(2, uuid.toString())
                        it.executeUpdate()
                    }
                }
            }
        }
    }

    override fun setCandidateStepdown(termIndex: Int, uuid: UUID) = writeState {
        val rec = candidatesByTerm[termIndex]?.get(uuid) ?: return@writeState
        rec.status = CandidateStatus.REMOVED
        rec.stepdown = true
        removeVotesForCandidate(termIndex, uuid)
        enqueueWrite { c ->
            runInTransaction(c) {
                upsertCandidateDb(c, termIndex, rec)
                c.prepareStatement("DELETE FROM votes WHERE term=? AND candidate_uuid=?").use {
                    it.setInt(1, termIndex)
                    it.setString(2, uuid.toString())
                    it.executeUpdate()
                }
            }
        }
    }

    override fun candidateSteppedDown(termIndex: Int, uuid: UUID): Boolean = readState {
        candidatesByTerm[termIndex]?.get(uuid)?.stepdown ?: false
    }

    override fun isPerksLocked(termIndex: Int, candidate: UUID): Boolean = readState {
        candidatesByTerm[termIndex]?.get(candidate)?.perksLocked ?: false
    }

    override fun setPerksLocked(termIndex: Int, candidate: UUID, locked: Boolean) = writeState {
        val rec = candidatesByTerm[termIndex]?.get(candidate) ?: return@writeState
        rec.perksLocked = locked
        upsertCandidate(termIndex, rec)
    }

    // ------------------------------------------------------------------------
    // Voting
    // ------------------------------------------------------------------------

    override fun hasVoted(termIndex: Int, voter: UUID): Boolean = readState {
        votesByTerm[termIndex]?.containsKey(voter) == true
    }

    override fun votedFor(termIndex: Int, voter: UUID): UUID? = readState {
        votesByTerm[termIndex]?.get(voter)
    }

    override fun vote(termIndex: Int, voter: UUID, candidate: UUID) = writeState {
        val votes = votesByTerm.computeIfAbsent(termIndex) { ConcurrentHashMap() }
        val counts = voteCountsByTerm.computeIfAbsent(termIndex) { ConcurrentHashMap() }
        val prev = votes[voter]
        if (prev == candidate) {
            return@writeState
        }

        votes[voter] = candidate
        if (prev != null && prev != candidate) {
            val next = (counts[prev] ?: 1) - 1
            if (next <= 0) {
                counts.remove(prev)
            } else {
                counts[prev] = next
            }
        }
        counts[candidate] = (counts[candidate] ?: 0) + 1

        enqueueWrite { c ->
            c.prepareStatement(
                "INSERT INTO votes(term, voter_uuid, candidate_uuid) VALUES(?,?,?) " +
                    "ON DUPLICATE KEY UPDATE candidate_uuid=VALUES(candidate_uuid)"
            ).use {
                it.setInt(1, termIndex)
                it.setString(2, voter.toString())
                it.setString(3, candidate.toString())
                it.executeUpdate()
            }
        }
    }

    override fun realVoteCounts(termIndex: Int): Map<UUID, Int> = readState {
        voteCountsByTerm[termIndex]?.toMap() ?: emptyMap()
    }

    override fun fakeVoteAdjustments(termIndex: Int): Map<UUID, Int> = readState {
        fakeVotesByTerm[termIndex]?.toMap() ?: emptyMap()
    }

    override fun fakeVoteAdjustment(termIndex: Int, candidate: UUID): Int = readState {
        fakeVotesByTerm[termIndex]?.get(candidate) ?: 0
    }

    override fun setFakeVoteAdjustment(termIndex: Int, candidate: UUID, amount: Int) = writeState {
        val fake = fakeVotesByTerm.computeIfAbsent(termIndex) { ConcurrentHashMap() }
        if (amount == 0) {
            fake.remove(candidate)
        } else {
            fake[candidate] = amount
        }

        enqueueWrite { c ->
            if (amount == 0) {
                c.prepareStatement("DELETE FROM fake_votes WHERE term=? AND candidate_uuid=?").use {
                    it.setInt(1, termIndex)
                    it.setString(2, candidate.toString())
                    it.executeUpdate()
                }
            } else {
                c.prepareStatement(
                    "INSERT INTO fake_votes(term, candidate_uuid, amount) VALUES(?,?,?) " +
                        "ON DUPLICATE KEY UPDATE amount=VALUES(amount)"
                ).use {
                    it.setInt(1, termIndex)
                    it.setString(2, candidate.toString())
                    it.setInt(3, amount)
                    it.executeUpdate()
                }
            }
        }
    }

    override fun voteCounts(termIndex: Int): Map<UUID, Int> = readState {
        combinedVoteCounts(termIndex)
    }

    override fun pickWinner(
        termIndex: Int,
        tiePolicy: TiePolicy,
        incumbent: UUID?,
        seededRngSeed: Long,
        logDecision: ((String) -> Unit)?
    ): CandidateEntry? = readState {
        val eligible = candidatesByTerm[termIndex]?.values
            ?.map { CandidateEntry(it.uuid, it.name, it.status) }
            ?.filter { it.status == CandidateStatus.ACTIVE }
            ?: emptyList()

        if (eligible.isEmpty()) return@readState null

        val counts = combinedVoteCounts(termIndex)
        val max = eligible.maxOf { counts[it.uuid] ?: 0 }

        if (max <= 0) {
            return@readState resolveTie(
                termIndex = termIndex,
                candidates = eligible,
                tiePolicy = tiePolicy,
                incumbent = incumbent,
                seededRngSeed = seededRngSeed,
                logDecision = logDecision,
                reason = "no votes"
            )
        }

        val top = eligible.filter { (counts[it.uuid] ?: 0) == max }
        if (top.size == 1) return@readState top.first()

        resolveTie(
            termIndex = termIndex,
            candidates = top,
            tiePolicy = tiePolicy,
            incumbent = incumbent,
            seededRngSeed = seededRngSeed,
            logDecision = logDecision,
            reason = "tied top votes=$max"
        )
    }

    private fun resolveTie(
        termIndex: Int,
        candidates: List<CandidateEntry>,
        tiePolicy: TiePolicy,
        incumbent: UUID?,
        seededRngSeed: Long,
        logDecision: ((String) -> Unit)?,
        reason: String
    ): CandidateEntry {
        val names = candidates.joinToString { it.lastKnownName }

        fun fallbackAlphabetical(): CandidateEntry =
            candidates.minBy { it.lastKnownName.lowercase() }

        val chosen: CandidateEntry = when (tiePolicy) {
            TiePolicy.ALPHABETICAL -> fallbackAlphabetical()

            TiePolicy.INCUMBENT -> {
                val inc = incumbent?.let { id -> candidates.firstOrNull { it.uuid == id } }
                inc ?: fallbackAlphabetical()
            }

            TiePolicy.EARLIEST_APPLICATION -> {
                val withTimes = candidates.map { it to (candidateAppliedAt(termIndex, it.uuid) ?: Instant.EPOCH) }
                withTimes
                    .sortedWith(compareBy<Pair<CandidateEntry, Instant>> { it.second }
                        .thenBy { it.first.lastKnownName.lowercase() })
                    .first().first
            }

            TiePolicy.SEEDED_RANDOM -> {
                val rng = Random(seededRngSeed)
                candidates[rng.nextInt(candidates.size)]
            }
        }

        logDecision?.invoke(
            "Tie resolved for electionTerm=$termIndex ($reason). Candidates=[$names], policy=$tiePolicy -> chosen=${chosen.lastKnownName}"
        )
        return chosen
    }

    override fun topCandidates(term: Int, limit: Int, includeRemoved: Boolean): List<Pair<CandidateEntry, Int>> = readState {
        val votes = combinedVoteCounts(term)
        val candidates = candidatesByTerm[term]?.values
            ?.map { CandidateEntry(it.uuid, it.name, it.status) }
            ?.filter { includeRemoved || it.status != CandidateStatus.REMOVED }
            ?: emptyList()

        candidates
            .map { it to (votes[it.uuid] ?: 0) }
            .sortedWith(compareByDescending<Pair<CandidateEntry, Int>> { it.second }
                .thenBy { it.first.lastKnownName.lowercase() })
            .take(limit)
    }

    // ------------------------------------------------------------------------
    // Candidate perks
    // ------------------------------------------------------------------------

    override fun chosenPerks(termIndex: Int, candidate: UUID): Set<String> = readState {
        candidatesByTerm[termIndex]?.get(candidate)?.perks?.toSet() ?: emptySet()
    }

    override fun setChosenPerks(termIndex: Int, candidate: UUID, perks: Set<String>) = writeState {
        val rec = candidatesByTerm[termIndex]?.get(candidate) ?: return@writeState
        rec.perks.clear()
        rec.perks.addAll(perks)
        enqueueWrite { c ->
            runInTransaction(c) {
                c.prepareStatement("DELETE FROM candidate_perks WHERE term=? AND uuid=?").use {
                    it.setInt(1, termIndex)
                    it.setString(2, candidate.toString())
                    it.executeUpdate()
                }
                c.prepareStatement("INSERT INTO candidate_perks(term, uuid, perk_id) VALUES(?,?,?)").use { ps ->
                    for (perk in perks) {
                        ps.setInt(1, termIndex)
                        ps.setString(2, candidate.toString())
                        ps.setString(3, perk)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Custom perk requests
    // ------------------------------------------------------------------------

    override fun addRequest(termIndex: Int, candidate: UUID, title: String, description: String): Int = writeState {
        val next = requestNextId.computeIfAbsent(termIndex) { AtomicInteger(1) }
        val id = next.getAndIncrement()
        val record = RequestRecord(
            id = id,
            candidate = candidate,
            title = title,
            description = description,
            status = RequestStatus.PENDING,
            createdAt = OffsetDateTime.now(),
            onStart = emptyList(),
            onEnd = emptyList()
        )
        requestsByTerm.computeIfAbsent(termIndex) { ConcurrentHashMap() }[id] = record
        enqueueWrite { c ->
            c.prepareStatement(
                "INSERT INTO requests(term,id,candidate_uuid,title,description,status,created_at,on_start,on_end) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)"
            ).use {
                bindRequest(it, termIndex, record)
                it.executeUpdate()
            }
        }
        id
    }

    override fun listRequests(termIndex: Int, status: RequestStatus?): List<CustomPerkRequest> = readState {
        val map = requestsByTerm[termIndex] ?: return@readState emptyList()
        map.values
            .filter { status == null || it.status == status }
            .sortedBy { it.id }
            .map { it.toPublic() }
    }

    override fun requestById(termIndex: Int, requestId: Int): CustomPerkRequest? = readState {
        val rec = requestsByTerm[termIndex]?.get(requestId) ?: return@readState null
        rec.toPublic()
    }

    override fun listRequestsForCandidate(termIndex: Int, candidate: UUID, status: RequestStatus?): List<CustomPerkRequest> = readState {
        val map = requestsByTerm[termIndex] ?: return@readState emptyList()
        map.values
            .filter { it.candidate == candidate && (status == null || it.status == status) }
            .sortedBy { it.id }
            .map { it.toPublic() }
    }

    override fun setRequestStatus(termIndex: Int, requestId: Int, status: RequestStatus) = writeState {
        val record = requestsByTerm[termIndex]?.get(requestId) ?: return@writeState
        record.status = status
        val candidate = record.candidate
        val customId = "custom:$requestId"
        val removedCustomPerk = status != RequestStatus.APPROVED &&
            candidatesByTerm[termIndex]?.get(candidate)?.perks?.remove(customId) == true
        enqueueWrite { c ->
            runInTransaction(c) {
                if (removedCustomPerk) {
                    c.prepareStatement("DELETE FROM candidate_perks WHERE term=? AND uuid=? AND perk_id=?").use {
                        it.setInt(1, termIndex)
                        it.setString(2, candidate.toString())
                        it.setString(3, customId)
                        it.executeUpdate()
                    }
                }
                c.prepareStatement(
                    "UPDATE requests SET status=? WHERE term=? AND id=?"
                ).use {
                    it.setString(1, status.name)
                    it.setInt(2, termIndex)
                    it.setInt(3, requestId)
                    it.executeUpdate()
                }
            }
        }
    }

    override fun setRequestCommands(termIndex: Int, requestId: Int, onStart: List<String>, onEnd: List<String>) = writeState {
        val record = requestsByTerm[termIndex]?.get(requestId) ?: return@writeState
        record.onStart = onStart
        record.onEnd = onEnd
        enqueueWrite { c ->
            c.prepareStatement(
                "UPDATE requests SET on_start=?, on_end=? WHERE term=? AND id=?"
            ).use {
                it.setString(1, gson.toJson(onStart))
                it.setString(2, gson.toJson(onEnd))
                it.setInt(3, termIndex)
                it.setInt(4, requestId)
                it.executeUpdate()
            }
        }
    }

    override fun requestCountForCandidate(term: Int, candidate: UUID): Int = readState {
        requestsByTerm[term]?.values?.count { it.candidate == candidate } ?: 0
    }

    override fun removeRequests(termIndex: Int, requestIds: Set<Int>) = writeState {
        if (requestIds.isEmpty()) return@writeState
        val map = requestsByTerm[termIndex] ?: return@writeState
        for (id in requestIds) {
            map.remove(id)
        }
        val ids = requestIds.toList()
        val placeholders = ids.joinToString(",") { "?" }
        enqueueWrite { c ->
            c.prepareStatement("DELETE FROM requests WHERE term=? AND id IN ($placeholders)").use { ps ->
                ps.setInt(1, termIndex)
                var idx = 2
                for (id in ids) {
                    ps.setInt(idx++, id)
                }
                ps.executeUpdate()
            }
        }
    }

    override fun clearRequests(termIndex: Int) = writeState {
        requestsByTerm[termIndex]?.clear()
        requestNextId[termIndex]?.set(1)
        enqueueWrite { c ->
            c.prepareStatement("DELETE FROM requests WHERE term=?").use {
                it.setInt(1, termIndex)
                it.executeUpdate()
            }
        }
    }

    override fun clearUnapprovedRequests(termIndex: Int) = writeState {
        val map = requestsByTerm[termIndex] ?: return@writeState
        val toRemove = map.values.filter { it.status != RequestStatus.APPROVED }.map { it.id }
        for (id in toRemove) {
            map.remove(id)
        }
        enqueueWrite { c ->
            c.prepareStatement("DELETE FROM requests WHERE term=? AND status<>?").use {
                it.setInt(1, termIndex)
                it.setString(2, RequestStatus.APPROVED.name)
                it.executeUpdate()
            }
        }
    }

    // ------------------------------------------------------------------------
    // Apply bans (global)
    // ------------------------------------------------------------------------

    override fun activeApplyBan(uuid: UUID): ApplyBan? = writeState {
        val ban = applyBans[uuid] ?: return@writeState null
        if (ban.permanent) return@writeState ban
        val until = ban.until ?: return@writeState ban
        if (OffsetDateTime.now().isBefore(until)) return@writeState ban
        applyBans.remove(uuid)
        enqueueWrite { c ->
            c.prepareStatement("DELETE FROM apply_bans WHERE uuid=?").use {
                it.setString(1, uuid.toString())
                it.executeUpdate()
            }
        }
        null
    }

    override fun setApplyBanPermanent(uuid: UUID, lastKnownName: String) = writeState {
        val ban = ApplyBan(uuid = uuid, lastKnownName = lastKnownName, permanent = true, until = null, createdAt = OffsetDateTime.now())
        applyBans[uuid] = ban
        enqueueWrite { c ->
            c.prepareStatement(
                "INSERT INTO apply_bans(uuid,name,permanent,until,created_at) VALUES(?,?,?,?,?) " +
                    "ON DUPLICATE KEY UPDATE name=VALUES(name), permanent=VALUES(permanent), until=VALUES(until), created_at=VALUES(created_at)"
            ).use {
                it.setString(1, uuid.toString())
                it.setString(2, lastKnownName)
                it.setInt(3, 1)
                it.setString(4, null)
                it.setString(5, ban.createdAt.toString())
                it.executeUpdate()
            }
        }
    }

    override fun setApplyBanTemp(uuid: UUID, lastKnownName: String, until: OffsetDateTime) = writeState {
        val ban = ApplyBan(uuid = uuid, lastKnownName = lastKnownName, permanent = false, until = until, createdAt = OffsetDateTime.now())
        applyBans[uuid] = ban
        enqueueWrite { c ->
            c.prepareStatement(
                "INSERT INTO apply_bans(uuid,name,permanent,until,created_at) VALUES(?,?,?,?,?) " +
                    "ON DUPLICATE KEY UPDATE name=VALUES(name), permanent=VALUES(permanent), until=VALUES(until), created_at=VALUES(created_at)"
            ).use {
                it.setString(1, uuid.toString())
                it.setString(2, lastKnownName)
                it.setInt(3, 0)
                it.setString(4, until.toString())
                it.setString(5, ban.createdAt.toString())
                it.executeUpdate()
            }
        }
    }

    override fun clearApplyBan(uuid: UUID) = writeState {
        applyBans.remove(uuid)
        enqueueWrite { c ->
            c.prepareStatement("DELETE FROM apply_bans WHERE uuid=?").use {
                it.setString(1, uuid.toString())
                it.executeUpdate()
            }
        }
    }

    override fun listApplyBans(): List<ApplyBan> = readState {
        applyBans.values.sortedBy { it.lastKnownName.lowercase() }
    }

    override fun resetTermData() = writeState {
        winners.clear()
        winnerNames.clear()
        termFlags.clear()
        candidatesByTerm.clear()
        votesByTerm.clear()
        voteCountsByTerm.clear()
        fakeVotesByTerm.clear()
        requestsByTerm.clear()
        requestNextId.clear()
        everMayors.clear()
        enqueueWrite { c ->
            runInTransaction(c) {
                c.prepareStatement("DELETE FROM terms").use { it.executeUpdate() }
                c.prepareStatement("DELETE FROM term_flags").use { it.executeUpdate() }
                c.prepareStatement("DELETE FROM candidates").use { it.executeUpdate() }
                c.prepareStatement("DELETE FROM candidate_perks").use { it.executeUpdate() }
                c.prepareStatement("DELETE FROM votes").use { it.executeUpdate() }
                c.prepareStatement("DELETE FROM fake_votes").use { it.executeUpdate() }
                c.prepareStatement("DELETE FROM requests").use { it.executeUpdate() }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private inline fun <T> readState(block: () -> T): T = stateLock.read { block() }

    private inline fun <T> writeState(block: () -> T): T = stateLock.write { block() }

    override fun candidateEntry(termIndex: Int, uuid: UUID): CandidateEntry? {
        return readState {
            val rec = candidatesByTerm[termIndex]?.get(uuid) ?: return@readState null
            CandidateEntry(uuid = rec.uuid, lastKnownName = rec.name, status = rec.status)
        }
    }

    private fun enqueueWrite(action: (Connection) -> Unit) {
        if (!initialized.get()) return
        if (strictWrites || !asyncWrites) {
            writeLock.withLock {
                dataSource.connection.use { action(it) }
            }
            return
        }
        executor.submit {
            writeLock.withLock {
                dataSource.connection.use { action(it) }
            }
        }
    }

    private fun buildJdbcUrl(): String {
        val base = "jdbc:mysql://$host:$port/$database"
        val extras = mutableListOf(
            "useSSL=$useSsl",
            "serverTimezone=$serverTimezone",
            "useUnicode=true",
            "characterEncoding=UTF-8"
        )
        val trimmed = params.trim()
        if (trimmed.isNotEmpty()) {
            extras.add(trimmed.trimStart('?', '&'))
        }
        return base + "?" + extras.joinToString("&")
    }

    private fun initSchema(c: Connection) {
        c.createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS meta (" +
                    "`key` VARCHAR(64) PRIMARY KEY, " +
                    "value TEXT" +
                ") ENGINE=InnoDB"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS terms (" +
                    "term INT PRIMARY KEY, " +
                    "winner_uuid VARCHAR(36), " +
                    "winner_name VARCHAR(64)" +
                    ") ENGINE=InnoDB"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS term_flags (" +
                    "term INT PRIMARY KEY, " +
                    "election_open_announced TINYINT(1) DEFAULT 0, " +
                    "mayor_elected_announced TINYINT(1) DEFAULT 0" +
                    ") ENGINE=InnoDB"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS candidates (" +
                    "term INT, " +
                    "uuid VARCHAR(36), " +
                    "name VARCHAR(64), " +
                    "status VARCHAR(16), " +
                    "applied_at VARCHAR(64), " +
                    "bio TEXT, " +
                    "perks_locked TINYINT(1) DEFAULT 0, " +
                    "stepdown TINYINT(1) DEFAULT 0, " +
                    "PRIMARY KEY(term, uuid), " +
                    "INDEX idx_candidates_term (term), " +
                    "INDEX idx_candidates_term_status (term, status)" +
                    ") ENGINE=InnoDB"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS candidate_perks (" +
                    "term INT, " +
                    "uuid VARCHAR(36), " +
                    "perk_id VARCHAR(64), " +
                    "PRIMARY KEY(term, uuid, perk_id), " +
                    "INDEX idx_candidate_perks_term (term)" +
                    ") ENGINE=InnoDB"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS votes (" +
                    "term INT, " +
                    "voter_uuid VARCHAR(36), " +
                    "candidate_uuid VARCHAR(36), " +
                    "PRIMARY KEY(term, voter_uuid), " +
                    "INDEX idx_votes_term (term)" +
                    ") ENGINE=InnoDB"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS fake_votes (" +
                    "term INT, " +
                    "candidate_uuid VARCHAR(36), " +
                    "amount INT NOT NULL, " +
                    "PRIMARY KEY(term, candidate_uuid), " +
                    "INDEX idx_fake_votes_term (term)" +
                    ") ENGINE=InnoDB"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS requests (" +
                    "term INT, " +
                    "id INT, " +
                    "candidate_uuid VARCHAR(36), " +
                    "title VARCHAR(128), " +
                    "description TEXT, " +
                    "status VARCHAR(16), " +
                    "created_at VARCHAR(64), " +
                    "on_start TEXT, " +
                    "on_end TEXT, " +
                    "PRIMARY KEY(term, id), " +
                    "INDEX idx_requests_term (term)" +
                    ") ENGINE=InnoDB"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS apply_bans (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "name VARCHAR(64), " +
                    "permanent TINYINT(1), " +
                    "until VARCHAR(64), " +
                    "created_at VARCHAR(64)" +
                ") ENGINE=InnoDB"
            )
        }
    }

    private fun loadAll(c: Connection) = writeState {
        c.createStatement().use { st ->
            st.executeQuery("SELECT term, winner_uuid, winner_name FROM terms").use { rs ->
                while (rs.next()) {
                    val term = rs.getInt("term")
                    val winner = rs.getString("winner_uuid")
                    val name = rs.getString("winner_name")
                    if (winner != null) {
                        runCatching { UUID.fromString(winner) }.getOrNull()?.let {
                            winners[term] = it
                            everMayors.add(it)
                        }
                    }
                    if (name != null) winnerNames[term] = name
                }
            }
            st.executeQuery("SELECT term, election_open_announced, mayor_elected_announced FROM term_flags").use { rs ->
                while (rs.next()) {
                    val term = rs.getInt("term")
                    termFlags[term] = TermFlags(
                        electionOpenAnnounced = rs.getInt("election_open_announced") != 0,
                        mayorElectedAnnounced = rs.getInt("mayor_elected_announced") != 0
                    )
                }
            }
            st.executeQuery(
                "SELECT term, uuid, name, status, applied_at, bio, perks_locked, stepdown FROM candidates"
            ).use { rs ->
                while (rs.next()) {
                    val term = rs.getInt("term")
                    val uuid = UUID.fromString(rs.getString("uuid"))
                    val rec = CandidateRecord(
                        uuid = uuid,
                        name = rs.getString("name") ?: "Unknown",
                        status = runCatching { CandidateStatus.valueOf(rs.getString("status")) }
                            .getOrElse { CandidateStatus.ACTIVE },
                        appliedAt = rs.getString("applied_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        bio = rs.getString("bio") ?: "",
                        perksLocked = rs.getInt("perks_locked") != 0,
                        stepdown = rs.getInt("stepdown") != 0,
                        perks = mutableSetOf()
                    )
                    candidatesByTerm.computeIfAbsent(term) { ConcurrentHashMap() }[uuid] = rec
                }
            }
            st.executeQuery("SELECT term, uuid, perk_id FROM candidate_perks").use { rs ->
                while (rs.next()) {
                    val term = rs.getInt("term")
                    val uuid = UUID.fromString(rs.getString("uuid"))
                    val perk = rs.getString("perk_id") ?: continue
                    candidatesByTerm[term]?.get(uuid)?.perks?.add(perk)
                }
            }
            st.executeQuery("SELECT term, voter_uuid, candidate_uuid FROM votes").use { rs ->
                while (rs.next()) {
                    val term = rs.getInt("term")
                    val voter = UUID.fromString(rs.getString("voter_uuid"))
                    val candidate = UUID.fromString(rs.getString("candidate_uuid"))
                    votesByTerm.computeIfAbsent(term) { ConcurrentHashMap() }[voter] = candidate
                    val counts = voteCountsByTerm.computeIfAbsent(term) { ConcurrentHashMap() }
                    counts[candidate] = (counts[candidate] ?: 0) + 1
                }
            }
            st.executeQuery("SELECT term, candidate_uuid, amount FROM fake_votes").use { rs ->
                while (rs.next()) {
                    val term = rs.getInt("term")
                    val candidate = UUID.fromString(rs.getString("candidate_uuid"))
                    val amount = rs.getInt("amount")
                    if (amount != 0) {
                        fakeVotesByTerm.computeIfAbsent(term) { ConcurrentHashMap() }[candidate] = amount
                    }
                }
            }
            st.executeQuery(
                "SELECT term, id, candidate_uuid, title, description, status, created_at, on_start, on_end FROM requests"
            ).use { rs ->
                while (rs.next()) {
                    val term = rs.getInt("term")
                    val id = rs.getInt("id")
                    val record = RequestRecord(
                        id = id,
                        candidate = UUID.fromString(rs.getString("candidate_uuid")),
                        title = rs.getString("title") ?: "Untitled",
                        description = rs.getString("description") ?: "",
                        status = runCatching { RequestStatus.valueOf(rs.getString("status")) }
                            .getOrElse { RequestStatus.PENDING },
                        createdAt = runCatching { OffsetDateTime.parse(rs.getString("created_at")) }
                            .getOrElse { OffsetDateTime.now() },
                        onStart = parseList(rs.getString("on_start")),
                        onEnd = parseList(rs.getString("on_end"))
                    )
                    requestsByTerm.computeIfAbsent(term) { ConcurrentHashMap() }[id] = record
                    requestNextId.computeIfAbsent(term) { AtomicInteger(1) }.updateAndGet { maxOf(it, id + 1) }
                }
            }
            st.executeQuery("SELECT uuid, name, permanent, until, created_at FROM apply_bans").use { rs ->
                while (rs.next()) {
                    val uuid = UUID.fromString(rs.getString("uuid"))
                    val permanent = rs.getInt("permanent") != 0
                    val untilStr = rs.getString("until")
                    val ban = ApplyBan(
                        uuid = uuid,
                        lastKnownName = rs.getString("name") ?: "Unknown",
                        permanent = permanent,
                        until = if (permanent) null else untilStr?.let { OffsetDateTime.parse(it) },
                        createdAt = runCatching { OffsetDateTime.parse(rs.getString("created_at")) }
                            .getOrElse { OffsetDateTime.now() }
                    )
                    applyBans[uuid] = ban
                }
            }
        }
    }

    private fun upsertFlags(termIndex: Int, flags: TermFlags) {
        enqueueWrite { c ->
            c.prepareStatement(
                "INSERT INTO term_flags(term, election_open_announced, mayor_elected_announced) VALUES(?,?,?) " +
                    "ON DUPLICATE KEY UPDATE election_open_announced=VALUES(election_open_announced), mayor_elected_announced=VALUES(mayor_elected_announced)"
            ).use {
                it.setInt(1, termIndex)
                it.setInt(2, if (flags.electionOpenAnnounced) 1 else 0)
                it.setInt(3, if (flags.mayorElectedAnnounced) 1 else 0)
                it.executeUpdate()
            }
        }
    }

    private fun upsertCandidate(termIndex: Int, rec: CandidateRecord) {
        enqueueWrite { c -> upsertCandidateDb(c, termIndex, rec) }
    }

    private fun upsertCandidateDb(c: Connection, termIndex: Int, rec: CandidateRecord) {
        c.prepareStatement(
            "INSERT INTO candidates(term, uuid, name, status, applied_at, bio, perks_locked, stepdown) " +
                "VALUES(?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE " +
                "name=VALUES(name), status=VALUES(status), applied_at=VALUES(applied_at), bio=VALUES(bio), " +
                "perks_locked=VALUES(perks_locked), stepdown=VALUES(stepdown)"
        ).use {
            it.setInt(1, termIndex)
            it.setString(2, rec.uuid.toString())
            it.setString(3, rec.name)
            it.setString(4, rec.status.name)
            it.setString(5, rec.appliedAt?.toString())
            it.setString(6, rec.bio)
            it.setInt(7, if (rec.perksLocked) 1 else 0)
            it.setInt(8, if (rec.stepdown) 1 else 0)
            it.executeUpdate()
        }
    }

    private fun bindRequest(ps: PreparedStatement, termIndex: Int, record: RequestRecord) {
        ps.setInt(1, termIndex)
        ps.setInt(2, record.id)
        ps.setString(3, record.candidate.toString())
        ps.setString(4, record.title)
        ps.setString(5, record.description)
        ps.setString(6, record.status.name)
        ps.setString(7, record.createdAt.toString())
        ps.setString(8, gson.toJson(record.onStart))
        ps.setString(9, gson.toJson(record.onEnd))
    }

    private fun runInTransaction(c: Connection, block: () -> Unit) {
        val prev = c.autoCommit
        c.autoCommit = false
        try {
            block()
            c.commit()
        } catch (t: Throwable) {
            c.rollback()
            throw t
        } finally {
            c.autoCommit = prev
        }
    }

    private fun removeVotesForCandidate(termIndex: Int, candidate: UUID) {
        val votes = votesByTerm[termIndex] ?: return
        val counts = voteCountsByTerm.computeIfAbsent(termIndex) { ConcurrentHashMap() }
        val toRemove = votes.filterValues { it == candidate }.keys
        for (voter in toRemove) {
            votes.remove(voter)
        }
        counts.remove(candidate)
    }

    private fun combinedVoteCounts(termIndex: Int): Map<UUID, Int> {
        val real = voteCountsByTerm[termIndex]
        val fake = fakeVotesByTerm[termIndex]
        if (real == null && fake == null) return emptyMap()

        val out = HashMap<UUID, Int>()
        real?.forEach { (candidate, amount) ->
            if (amount > 0) out[candidate] = amount
        }
        fake?.forEach { (candidate, amount) ->
            val total = (out[candidate] ?: 0) + amount
            if (total <= 0) {
                out.remove(candidate)
            } else {
                out[candidate] = total
            }
        }
        return out
    }

    private fun parseList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<String>>(raw, listType) }.getOrElse { emptyList() }
    }

    private data class TermFlags(
        var electionOpenAnnounced: Boolean = false,
        var mayorElectedAnnounced: Boolean = false
    )

    private data class CandidateRecord(
        val uuid: UUID,
        var name: String,
        var status: CandidateStatus,
        var appliedAt: Instant?,
        var bio: String,
        var perksLocked: Boolean,
        var stepdown: Boolean,
        val perks: MutableSet<String>
    )

    private data class RequestRecord(
        val id: Int,
        val candidate: UUID,
        var title: String,
        var description: String,
        var status: RequestStatus,
        var createdAt: OffsetDateTime,
        var onStart: List<String>,
        var onEnd: List<String>
    ) {
        fun toPublic(): CustomPerkRequest = CustomPerkRequest(
            id = id,
            candidate = candidate,
            title = title,
            description = description,
            status = status,
            createdAt = createdAt,
            onStart = onStart,
            onEnd = onEnd
        )
    }
}

