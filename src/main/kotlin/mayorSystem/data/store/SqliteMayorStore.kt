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
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

class SqliteMayorStore(private val plugin: MayorPlugin) : StoreBackend {
    override val id: String = "sqlite"

    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type

    private val asyncWrites = plugin.config.getBoolean("data.store.sqlite.async_writes", true)
    private val strictWrites = plugin.config.getBoolean("data.store.sqlite.strict", true)
    private val dbFile = File(plugin.dataFolder, plugin.config.getString("data.store.sqlite.file") ?: "elections.db")
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MayorStore-SQLite").apply { isDaemon = true }
    }
    private val writeLock = ReentrantLock()
    private val conn: Connection = openConnection()

    private val winners = ConcurrentHashMap<Int, UUID>()
    private val winnerNames = ConcurrentHashMap<Int, String>()
    private val termFlags = ConcurrentHashMap<Int, TermFlags>()
    private val candidatesByTerm = ConcurrentHashMap<Int, ConcurrentHashMap<UUID, CandidateRecord>>()
    private val votesByTerm = ConcurrentHashMap<Int, ConcurrentHashMap<UUID, UUID>>()
    private val voteCountsByTerm = ConcurrentHashMap<Int, ConcurrentHashMap<UUID, Int>>()
    private val requestsByTerm = ConcurrentHashMap<Int, ConcurrentHashMap<Int, RequestRecord>>()
    private val requestNextId = ConcurrentHashMap<Int, AtomicInteger>()
    private val applyBans = ConcurrentHashMap<UUID, ApplyBan>()
    private val everMayors: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    private val initialized = AtomicBoolean(false)

    init {
        ensureParent()
        applyPragmas()
        initSchema()
        maybeMigrateFromYaml()
        loadAll()
        initialized.set(true)
    }

    override fun shutdown() {
        executor.shutdown()
        runCatching { executor.awaitTermination(5, TimeUnit.SECONDS) }
        runCatching { conn.close() }
    }

    override fun hasEverBeenMayor(uuid: UUID): Boolean = everMayors.contains(uuid)

    // ------------------------------------------------------------------------
    // Winner
    // ------------------------------------------------------------------------

    override fun winner(termIndex: Int): UUID? = winners[termIndex]

    override fun winnerName(termIndex: Int): String? = winnerNames[termIndex]

    override fun setWinner(termIndex: Int, uuid: UUID, lastKnownName: String) {
        winners[termIndex] = uuid
        winnerNames[termIndex] = lastKnownName
        everMayors.add(uuid)
        enqueueWrite { c ->
            c.prepareStatement(
                "INSERT INTO terms(term, winner_uuid, winner_name) VALUES(?,?,?) " +
                    "ON CONFLICT(term) DO UPDATE SET winner_uuid=excluded.winner_uuid, winner_name=excluded.winner_name"
            ).use {
                it.setInt(1, termIndex)
                it.setString(2, uuid.toString())
                it.setString(3, lastKnownName)
                it.executeUpdate()
            }
        }
    }

    override fun clearWinner(termIndex: Int) {
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

    override fun electionOpenAnnounced(termIndex: Int): Boolean =
        termFlags[termIndex]?.electionOpenAnnounced ?: false

    override fun setElectionOpenAnnounced(termIndex: Int, value: Boolean) {
        val flags = termFlags.computeIfAbsent(termIndex) { TermFlags() }
        flags.electionOpenAnnounced = value
        upsertFlags(termIndex, flags)
    }

    override fun mayorElectedAnnounced(termIndex: Int): Boolean =
        termFlags[termIndex]?.mayorElectedAnnounced ?: false

    override fun setMayorElectedAnnounced(termIndex: Int, value: Boolean) {
        val flags = termFlags.computeIfAbsent(termIndex) { TermFlags() }
        flags.mayorElectedAnnounced = value
        upsertFlags(termIndex, flags)
    }

    // ------------------------------------------------------------------------
    // Candidates
    // ------------------------------------------------------------------------

    override fun candidates(termIndex: Int, includeRemoved: Boolean): List<CandidateEntry> {
        val map = candidatesByTerm[termIndex] ?: return emptyList()
        return map.values
            .map { CandidateEntry(it.uuid, it.name, it.status) }
            .filter { includeRemoved || it.status != CandidateStatus.REMOVED }
    }

    override fun isCandidate(termIndex: Int, uuid: UUID): Boolean =
        candidatesByTerm[termIndex]?.containsKey(uuid) == true

    override fun setCandidate(termIndex: Int, uuid: UUID, name: String) {
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

    override fun candidateBio(termIndex: Int, candidate: UUID): String =
        candidatesByTerm[termIndex]?.get(candidate)?.bio ?: ""

    override fun setCandidateBio(termIndex: Int, candidate: UUID, bio: String) {
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

    override fun candidateAppliedAt(termIndex: Int, candidate: UUID): Instant? =
        candidatesByTerm[termIndex]?.get(candidate)?.appliedAt

    override fun setCandidateStatus(termIndex: Int, uuid: UUID, status: CandidateStatus) {
        writeLock.withLock {
            val rec = candidatesByTerm[termIndex]?.get(uuid) ?: return
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
    }

    override fun setCandidateStepdown(termIndex: Int, uuid: UUID) {
        writeLock.withLock {
            val rec = candidatesByTerm[termIndex]?.get(uuid) ?: return
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
    }

    override fun candidateSteppedDown(termIndex: Int, uuid: UUID): Boolean =
        candidatesByTerm[termIndex]?.get(uuid)?.stepdown ?: false

    override fun isPerksLocked(termIndex: Int, candidate: UUID): Boolean =
        candidatesByTerm[termIndex]?.get(candidate)?.perksLocked ?: false

    override fun setPerksLocked(termIndex: Int, candidate: UUID, locked: Boolean) {
        val rec = candidatesByTerm[termIndex]?.get(candidate) ?: return
        rec.perksLocked = locked
        upsertCandidate(termIndex, rec)
    }

    // ------------------------------------------------------------------------
    // Voting
    // ------------------------------------------------------------------------

    override fun hasVoted(termIndex: Int, voter: UUID): Boolean =
        votesByTerm[termIndex]?.containsKey(voter) == true

    override fun votedFor(termIndex: Int, voter: UUID): UUID? =
        votesByTerm[termIndex]?.get(voter)

    override fun vote(termIndex: Int, voter: UUID, candidate: UUID) {
        writeLock.withLock {
            val votes = votesByTerm.computeIfAbsent(termIndex) { ConcurrentHashMap() }
            val counts = voteCountsByTerm.computeIfAbsent(termIndex) { ConcurrentHashMap() }
            val prev = votes.put(voter, candidate)
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
                        "ON CONFLICT(term, voter_uuid) DO UPDATE SET candidate_uuid=excluded.candidate_uuid"
                ).use {
                    it.setInt(1, termIndex)
                    it.setString(2, voter.toString())
                    it.setString(3, candidate.toString())
                    it.executeUpdate()
                }
            }
        }
    }

    override fun voteCounts(termIndex: Int): Map<UUID, Int> =
        voteCountsByTerm[termIndex]?.toMap() ?: emptyMap()

    override fun pickWinner(
        termIndex: Int,
        tiePolicy: TiePolicy,
        incumbent: UUID?,
        seededRngSeed: Long,
        logDecision: ((String) -> Unit)?
    ): CandidateEntry? {
        val eligible = candidates(termIndex, includeRemoved = false)
            .filter { it.status == CandidateStatus.ACTIVE }

        if (eligible.isEmpty()) return null

        val counts = voteCounts(termIndex)
        val max = eligible.maxOf { counts[it.uuid] ?: 0 }

        if (max <= 0) {
            return resolveTie(
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
        if (top.size == 1) return top.first()

        return resolveTie(
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

    override fun topCandidates(term: Int, limit: Int, includeRemoved: Boolean): List<Pair<CandidateEntry, Int>> {
        val votes = voteCounts(term)
        return candidates(term, includeRemoved = includeRemoved)
            .map { it to (votes[it.uuid] ?: 0) }
            .sortedWith(compareByDescending<Pair<CandidateEntry, Int>> { it.second }
                .thenBy { it.first.lastKnownName.lowercase() })
            .take(limit)
    }

    // ------------------------------------------------------------------------
    // Candidate perks
    // ------------------------------------------------------------------------

    override fun chosenPerks(termIndex: Int, candidate: UUID): Set<String> =
        candidatesByTerm[termIndex]?.get(candidate)?.perks?.toSet() ?: emptySet()

    override fun setChosenPerks(termIndex: Int, candidate: UUID, perks: Set<String>) {
        val rec = candidatesByTerm[termIndex]?.get(candidate) ?: return
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

    override fun addRequest(termIndex: Int, candidate: UUID, title: String, description: String): Int {
        return writeLock.withLock {
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
    }

    override fun listRequests(termIndex: Int, status: RequestStatus?): List<CustomPerkRequest> {
        val map = requestsByTerm[termIndex] ?: return emptyList()
        return map.values
            .filter { status == null || it.status == status }
            .sortedBy { it.id }
            .map { it.toPublic() }
    }

    override fun setRequestStatus(termIndex: Int, requestId: Int, status: RequestStatus) {
        writeLock.withLock {
            val record = requestsByTerm[termIndex]?.get(requestId) ?: return
            record.status = status
            if (status != RequestStatus.APPROVED) {
                val candidate = record.candidate
                val perks = candidatesByTerm[termIndex]?.get(candidate)?.perks ?: mutableSetOf()
                val customId = "custom:$requestId"
                if (perks.remove(customId)) {
                    enqueueWrite { c ->
                        c.prepareStatement("DELETE FROM candidate_perks WHERE term=? AND uuid=? AND perk_id=?").use {
                            it.setInt(1, termIndex)
                            it.setString(2, candidate.toString())
                            it.setString(3, customId)
                            it.executeUpdate()
                        }
                    }
                }
            }
            enqueueWrite { c ->
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

    override fun setRequestCommands(termIndex: Int, requestId: Int, onStart: List<String>, onEnd: List<String>) {
        val record = requestsByTerm[termIndex]?.get(requestId) ?: return
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

    override fun requestCountForCandidate(term: Int, candidate: UUID): Int =
        requestsByTerm[term]?.values?.count { it.candidate == candidate } ?: 0

    override fun clearRequests(termIndex: Int) {
        requestsByTerm[termIndex]?.clear()
        requestNextId[termIndex]?.set(1)
        enqueueWrite { c ->
            c.prepareStatement("DELETE FROM requests WHERE term=?").use {
                it.setInt(1, termIndex)
                it.executeUpdate()
            }
        }
    }

    override fun clearUnapprovedRequests(termIndex: Int) {
        writeLock.withLock {
            val map = requestsByTerm[termIndex] ?: return
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
    }

    // ------------------------------------------------------------------------
    // Apply bans (global)
    // ------------------------------------------------------------------------

    override fun activeApplyBan(uuid: UUID): ApplyBan? {
        val ban = applyBans[uuid] ?: return null
        if (ban.permanent) return ban
        val until = ban.until ?: return ban
        if (OffsetDateTime.now().isBefore(until)) return ban
        applyBans.remove(uuid)
        enqueueWrite { c ->
            c.prepareStatement("DELETE FROM apply_bans WHERE uuid=?").use {
                it.setString(1, uuid.toString())
                it.executeUpdate()
            }
        }
        return null
    }

    override fun setApplyBanPermanent(uuid: UUID, lastKnownName: String) {
        val ban = ApplyBan(uuid = uuid, lastKnownName = lastKnownName, permanent = true, until = null, createdAt = OffsetDateTime.now())
        applyBans[uuid] = ban
        enqueueWrite { c ->
            c.prepareStatement(
                "INSERT INTO apply_bans(uuid,name,permanent,until,created_at) VALUES(?,?,?,?,?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, permanent=excluded.permanent, until=excluded.until, created_at=excluded.created_at"
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

    override fun setApplyBanTemp(uuid: UUID, lastKnownName: String, until: OffsetDateTime) {
        val ban = ApplyBan(uuid = uuid, lastKnownName = lastKnownName, permanent = false, until = until, createdAt = OffsetDateTime.now())
        applyBans[uuid] = ban
        enqueueWrite { c ->
            c.prepareStatement(
                "INSERT INTO apply_bans(uuid,name,permanent,until,created_at) VALUES(?,?,?,?,?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, permanent=excluded.permanent, until=excluded.until, created_at=excluded.created_at"
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

    override fun clearApplyBan(uuid: UUID) {
        applyBans.remove(uuid)
        enqueueWrite { c ->
            c.prepareStatement("DELETE FROM apply_bans WHERE uuid=?").use {
                it.setString(1, uuid.toString())
                it.executeUpdate()
            }
        }
    }

    override fun listApplyBans(): List<ApplyBan> =
        applyBans.values.sortedBy { it.lastKnownName.lowercase() }

    override fun resetTermData() {
        writeLock.withLock {
            winners.clear()
            winnerNames.clear()
            termFlags.clear()
            candidatesByTerm.clear()
            votesByTerm.clear()
            voteCountsByTerm.clear()
            requestsByTerm.clear()
            requestNextId.clear()
            everMayors.clear()
        }
        enqueueWrite { c ->
            runInTransaction(c) {
                c.prepareStatement("DELETE FROM terms").use { it.executeUpdate() }
                c.prepareStatement("DELETE FROM term_flags").use { it.executeUpdate() }
                c.prepareStatement("DELETE FROM candidates").use { it.executeUpdate() }
                c.prepareStatement("DELETE FROM candidate_perks").use { it.executeUpdate() }
                c.prepareStatement("DELETE FROM votes").use { it.executeUpdate() }
                c.prepareStatement("DELETE FROM requests").use { it.executeUpdate() }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    override fun candidateEntry(termIndex: Int, uuid: UUID): CandidateEntry? {
        val rec = candidatesByTerm[termIndex]?.get(uuid) ?: return null
        return CandidateEntry(uuid = rec.uuid, lastKnownName = rec.name, status = rec.status)
    }

    private fun enqueueWrite(action: (Connection) -> Unit) {
        if (!initialized.get()) return
        if (strictWrites || !asyncWrites) {
            writeLock.withLock { action(conn) }
            return
        }
        executor.submit {
            writeLock.withLock { action(conn) }
        }
    }

    private fun ensureParent() {
        if (!dbFile.parentFile.exists()) dbFile.parentFile.mkdirs()
    }

    private fun openConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

    private fun applyPragmas() {
        val journal = plugin.config.getString("data.store.sqlite.journal_mode", "WAL") ?: "WAL"
        val sync = plugin.config.getString("data.store.sqlite.synchronous", "NORMAL") ?: "NORMAL"
        val timeout = plugin.config.getInt("data.store.sqlite.busy_timeout_ms", 2000)
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=$journal")
            st.execute("PRAGMA synchronous=$sync")
            st.execute("PRAGMA busy_timeout=$timeout")
            st.execute("PRAGMA foreign_keys=ON")
        }
    }

    private fun initSchema() {
        conn.createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS meta (" +
                    "key TEXT PRIMARY KEY, " +
                    "value TEXT" +
                    ")"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS terms (" +
                    "term INTEGER PRIMARY KEY, " +
                    "winner_uuid TEXT, " +
                    "winner_name TEXT" +
                    ")"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS term_flags (" +
                    "term INTEGER PRIMARY KEY, " +
                    "election_open_announced INTEGER DEFAULT 0, " +
                    "mayor_elected_announced INTEGER DEFAULT 0" +
                    ")"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS candidates (" +
                    "term INTEGER, " +
                    "uuid TEXT, " +
                    "name TEXT, " +
                    "status TEXT, " +
                    "applied_at TEXT, " +
                    "bio TEXT, " +
                    "perks_locked INTEGER DEFAULT 0, " +
                    "stepdown INTEGER DEFAULT 0, " +
                    "PRIMARY KEY(term, uuid)" +
                    ")"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS candidate_perks (" +
                    "term INTEGER, " +
                    "uuid TEXT, " +
                    "perk_id TEXT, " +
                    "PRIMARY KEY(term, uuid, perk_id)" +
                    ")"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS votes (" +
                    "term INTEGER, " +
                    "voter_uuid TEXT, " +
                    "candidate_uuid TEXT, " +
                    "PRIMARY KEY(term, voter_uuid)" +
                    ")"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS requests (" +
                    "term INTEGER, " +
                    "id INTEGER, " +
                    "candidate_uuid TEXT, " +
                    "title TEXT, " +
                    "description TEXT, " +
                    "status TEXT, " +
                    "created_at TEXT, " +
                    "on_start TEXT, " +
                    "on_end TEXT, " +
                    "PRIMARY KEY(term, id)" +
                    ")"
            )
            st.execute(
                "CREATE TABLE IF NOT EXISTS apply_bans (" +
                    "uuid TEXT PRIMARY KEY, " +
                    "name TEXT, " +
                    "permanent INTEGER, " +
                    "until TEXT, " +
                    "created_at TEXT" +
                    ")"
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_candidates_term ON candidates(term)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_candidates_term_status ON candidates(term, status)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_votes_term ON votes(term)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_requests_term ON requests(term)")
        }
    }

    private fun maybeMigrateFromYaml() {
        val migrate = plugin.config.getBoolean("data.store.sqlite.migrate_from_yaml", true)
        if (!migrate) return
        val yamlFile = File(plugin.dataFolder, "elections.yml")
        if (!yamlFile.exists()) return
        val hasData = conn.prepareStatement("SELECT COUNT(*) FROM terms").use {
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) > 0 }
        }
        if (hasData) return
        migrateFromYaml(yamlFile)
    }

    private fun migrateFromYaml(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        conn.autoCommit = false
        try {
            val terms = yaml.getConfigurationSection("terms")
            if (terms != null) {
                for (termKey in terms.getKeys(false)) {
                    val term = termKey.toIntOrNull() ?: continue
                    val winner = yaml.getString("terms.$term.winner")
                    val winnerName = yaml.getString("terms.$term.winner_name")
                    if (winner != null) {
                        conn.prepareStatement(
                            "INSERT INTO terms(term, winner_uuid, winner_name) VALUES(?,?,?)"
                        ).use {
                            it.setInt(1, term)
                            it.setString(2, winner)
                            it.setString(3, winnerName)
                            it.executeUpdate()
                        }
                    }

                    val openAnnounced = yaml.getBoolean("terms.$term.flags.election_open_announced", false)
                    val electedAnnounced = yaml.getBoolean("terms.$term.flags.mayor_elected_announced", false)
                    conn.prepareStatement(
                        "INSERT INTO term_flags(term, election_open_announced, mayor_elected_announced) VALUES(?,?,?)"
                    ).use {
                        it.setInt(1, term)
                        it.setInt(2, if (openAnnounced) 1 else 0)
                        it.setInt(3, if (electedAnnounced) 1 else 0)
                        it.executeUpdate()
                    }

                    val candSec = yaml.getConfigurationSection("terms.$term.candidates")
                    if (candSec != null) {
                        for (candKey in candSec.getKeys(false)) {
                            val uuid = runCatching { UUID.fromString(candKey) }.getOrNull() ?: continue
                            val name = candSec.getString("$candKey.name") ?: "Unknown"
                            val status = candSec.getString("$candKey.status") ?: CandidateStatus.ACTIVE.name
                            val appliedAt = candSec.getString("$candKey.applied_at")
                            val bio = candSec.getString("$candKey.bio") ?: ""
                            val perksLocked = candSec.getBoolean("$candKey.perks_locked", false)
                            val stepdown = candSec.getBoolean("$candKey.stepdown", false)
                            conn.prepareStatement(
                                "INSERT INTO candidates(term, uuid, name, status, applied_at, bio, perks_locked, stepdown) " +
                                    "VALUES(?,?,?,?,?,?,?,?)"
                            ).use {
                                it.setInt(1, term)
                                it.setString(2, uuid.toString())
                                it.setString(3, name)
                                it.setString(4, status)
                                it.setString(5, appliedAt)
                                it.setString(6, bio)
                                it.setInt(7, if (perksLocked) 1 else 0)
                                it.setInt(8, if (stepdown) 1 else 0)
                                it.executeUpdate()
                            }

                            val perks = candSec.getStringList("$candKey.perks")
                            if (perks.isNotEmpty()) {
                                conn.prepareStatement("INSERT INTO candidate_perks(term, uuid, perk_id) VALUES(?,?,?)").use { ps ->
                                    for (perk in perks) {
                                        ps.setInt(1, term)
                                        ps.setString(2, uuid.toString())
                                        ps.setString(3, perk)
                                        ps.addBatch()
                                    }
                                    ps.executeBatch()
                                }
                            }
                        }
                    }

                    val voteSec = yaml.getConfigurationSection("terms.$term.votes")
                    if (voteSec != null) {
                        conn.prepareStatement("INSERT INTO votes(term, voter_uuid, candidate_uuid) VALUES(?,?,?)").use { ps ->
                            for (voterKey in voteSec.getKeys(false)) {
                                val votedFor = voteSec.getString(voterKey) ?: continue
                                ps.setInt(1, term)
                                ps.setString(2, voterKey)
                                ps.setString(3, votedFor)
                                ps.addBatch()
                            }
                            ps.executeBatch()
                        }
                    }

                    val reqSec = yaml.getConfigurationSection("terms.$term.requests.items")
                    if (reqSec != null) {
                        conn.prepareStatement(
                            "INSERT INTO requests(term,id,candidate_uuid,title,description,status,created_at,on_start,on_end) " +
                                "VALUES(?,?,?,?,?,?,?,?,?)"
                        ).use { ps ->
                            for (idStr in reqSec.getKeys(false)) {
                                val id = idStr.toIntOrNull() ?: continue
                                val cand = reqSec.getString("$idStr.candidate") ?: continue
                                val title = reqSec.getString("$idStr.title") ?: "Untitled"
                                val description = reqSec.getString("$idStr.description") ?: ""
                                val status = reqSec.getString("$idStr.status") ?: RequestStatus.PENDING.name
                                val createdAt = reqSec.getString("$idStr.createdAt") ?: OffsetDateTime.now().toString()
                                val onStart = gson.toJson(reqSec.getStringList("$idStr.onStart"))
                                val onEnd = gson.toJson(reqSec.getStringList("$idStr.onEnd"))
                                ps.setInt(1, term)
                                ps.setInt(2, id)
                                ps.setString(3, cand)
                                ps.setString(4, title)
                                ps.setString(5, description)
                                ps.setString(6, status)
                                ps.setString(7, createdAt)
                                ps.setString(8, onStart)
                                ps.setString(9, onEnd)
                                ps.addBatch()
                            }
                            ps.executeBatch()
                        }
                    }
                }
            }

            val bans = yaml.getConfigurationSection("apply_bans")
            if (bans != null) {
                conn.prepareStatement(
                    "INSERT INTO apply_bans(uuid,name,permanent,until,created_at) VALUES(?,?,?,?,?)"
                ).use { ps ->
                    for (key in bans.getKeys(false)) {
                        val name = bans.getString("$key.name") ?: "Unknown"
                        val permanent = bans.getBoolean("$key.permanent", false)
                        val until = bans.getString("$key.until")
                        val createdAt = bans.getString("$key.created_at") ?: OffsetDateTime.now().toString()
                        ps.setString(1, key)
                        ps.setString(2, name)
                        ps.setInt(3, if (permanent) 1 else 0)
                        ps.setString(4, until)
                        ps.setString(5, createdAt)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }

            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            plugin.logger.warning("[MayorStore] SQLite migration failed: ${t.message}")
        } finally {
            conn.autoCommit = true
        }
    }

    private fun loadAll() {
        conn.createStatement().use { st ->
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
                    "ON CONFLICT(term) DO UPDATE SET election_open_announced=excluded.election_open_announced, mayor_elected_announced=excluded.mayor_elected_announced"
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
                "ON CONFLICT(term, uuid) DO UPDATE SET " +
                "name=excluded.name, status=excluded.status, applied_at=excluded.applied_at, bio=excluded.bio, " +
                "perks_locked=excluded.perks_locked, stepdown=excluded.stepdown"
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

