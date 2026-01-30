package mayorSystem.data.store

import mayorSystem.MayorPlugin
import mayorSystem.config.TiePolicy
import mayorSystem.data.ApplyBan
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.CustomPerkRequest
import mayorSystem.data.RequestStatus
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

class YamlMayorStore(private val plugin: MayorPlugin) : StoreBackend {

    override val id: String = "yaml"

    private val file = File(plugin.dataFolder, "elections.yml")
    private val yaml: YamlConfiguration = YamlConfiguration.loadConfiguration(file)

    /**
     * Store writes are protected by a lock and written atomically to reduce
     * the odds of corruption if the server crashes mid-write.
     */
    private val ioLock = ReentrantLock()
    private val asyncSave = plugin.config.getBoolean("data.store.yaml.async_save", true)
    private val saveDelayTicks = plugin.config.getInt("data.store.yaml.save_delay_ticks", 20).coerceAtLeast(1)
    private val pendingSnapshot = AtomicReference<String?>(null)
    private val saveScheduled = AtomicBoolean(false)

    private fun scheduleSave() {
        if (!saveScheduled.compareAndSet(false, true)) return
        plugin.server.scheduler.runTaskLaterAsynchronously(plugin, Runnable {
            val snapshot = pendingSnapshot.getAndSet(null)
            saveScheduled.set(false)
            if (snapshot != null) {
                writeSnapshot(snapshot)
            }
            if (pendingSnapshot.get() != null) {
                scheduleSave()
            }
        }, saveDelayTicks.toLong())
    }

    private fun writeSnapshot(snapshot: String) = ioLock.withLock {
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        Files.writeString(tmp.toPath(), snapshot, StandardCharsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Throwable) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun save() {
        if (!asyncSave) {
            writeSnapshot(ioLock.withLock { yaml.saveToString() })
            return
        }
        val snapshot = ioLock.withLock { yaml.saveToString() }
        pendingSnapshot.set(snapshot)
        scheduleSave()
    }

    override fun shutdown() {
        val snapshot = pendingSnapshot.getAndSet(null) ?: return
        writeSnapshot(snapshot)
    }

    /**
     * Cached set of UUIDs that have won at least one term.
     */
    private val everMayors: MutableSet<UUID> = mutableSetOf<UUID>().also { set ->
        val terms = yaml.getConfigurationSection("terms") ?: return@also
        for (termKey in terms.getKeys(false)) {
            val win = yaml.getString("terms.$termKey.winner") ?: continue
            runCatching { UUID.fromString(win) }.getOrNull()?.let(set::add)
        }
    }

    override fun hasEverBeenMayor(uuid: UUID): Boolean = everMayors.contains(uuid)

    // ------------------------------------------------------------------------
    // Winner
    // ------------------------------------------------------------------------

    override fun winner(termIndex: Int): UUID? =
        yaml.getString("terms.$termIndex.winner")?.let(UUID::fromString)

    override fun winnerName(termIndex: Int): String? =
        yaml.getString("terms.$termIndex.winner_name")

    override fun setWinner(termIndex: Int, uuid: UUID, lastKnownName: String) {
        yaml.set("terms.$termIndex.winner", uuid.toString())
        yaml.set("terms.$termIndex.winner_name", lastKnownName)
        everMayors.add(uuid)
        save()
    }

    // ------------------------------------------------------------------------
    // Term flags
    // ------------------------------------------------------------------------

    override fun electionOpenAnnounced(termIndex: Int): Boolean =
        yaml.getBoolean("terms.$termIndex.flags.election_open_announced", false)

    override fun setElectionOpenAnnounced(termIndex: Int, value: Boolean) {
        yaml.set("terms.$termIndex.flags.election_open_announced", value)
        save()
    }

    override fun mayorElectedAnnounced(termIndex: Int): Boolean =
        yaml.getBoolean("terms.$termIndex.flags.mayor_elected_announced", false)

    override fun setMayorElectedAnnounced(termIndex: Int, value: Boolean) {
        yaml.set("terms.$termIndex.flags.mayor_elected_announced", value)
        save()
    }

    // ------------------------------------------------------------------------
    // Candidates
    // ------------------------------------------------------------------------

    override fun candidates(termIndex: Int, includeRemoved: Boolean): List<CandidateEntry> {
        val sec = yaml.getConfigurationSection("terms.$termIndex.candidates") ?: return emptyList()
        return sec.getKeys(false).mapNotNull { k ->
            val uuid = runCatching { UUID.fromString(k) }.getOrNull() ?: return@mapNotNull null
            val name = sec.getString("$k.name") ?: "Unknown"
            val status = runCatching {
                CandidateStatus.valueOf(sec.getString("$k.status") ?: CandidateStatus.ACTIVE.name)
            }.getOrElse { CandidateStatus.ACTIVE }

            if (!includeRemoved && status == CandidateStatus.REMOVED) return@mapNotNull null
            CandidateEntry(uuid, name, status)
        }
    }

    override fun isCandidate(termIndex: Int, uuid: UUID): Boolean =
        yaml.contains("terms.$termIndex.candidates.${uuid}")

    override fun setCandidate(termIndex: Int, uuid: UUID, name: String) {
        yaml.set("terms.$termIndex.candidates.${uuid}.name", name)
        yaml.set("terms.$termIndex.candidates.${uuid}.status", CandidateStatus.ACTIVE.name)
        yaml.set("terms.$termIndex.candidates.${uuid}.stepdown", false)

        val appliedAtPath = "terms.$termIndex.candidates.${uuid}.applied_at"
        if (!yaml.contains(appliedAtPath)) {
            yaml.set(appliedAtPath, Instant.now().toString())
        }

        val bioPath = "terms.$termIndex.candidates.${uuid}.bio"
        if (!yaml.contains(bioPath)) {
            yaml.set(bioPath, "")
        }

        if (!yaml.contains("terms.$termIndex.candidates.${uuid}.perks_locked")) {
            yaml.set("terms.$termIndex.candidates.${uuid}.perks_locked", false)
        }
        save()
    }

    override fun candidateBio(termIndex: Int, candidate: UUID): String =
        yaml.getString("terms.$termIndex.candidates.${candidate}.bio") ?: ""

    override fun setCandidateBio(termIndex: Int, candidate: UUID, bio: String) {
        yaml.set("terms.$termIndex.candidates.${candidate}.bio", bio)
        save()
    }

    override fun candidateAppliedAt(termIndex: Int, candidate: UUID): Instant? {
        val raw = yaml.getString("terms.$termIndex.candidates.${candidate}.applied_at") ?: return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    override fun setCandidateStatus(termIndex: Int, uuid: UUID, status: CandidateStatus) {
        yaml.set("terms.$termIndex.candidates.${uuid}.status", status.name)
        yaml.set("terms.$termIndex.candidates.${uuid}.stepdown", false)

        if (status == CandidateStatus.REMOVED) {
            val votes = yaml.getConfigurationSection("terms.$termIndex.votes") ?: run { save(); return }
            votes.getKeys(false).forEach { voter ->
                val votedFor = votes.getString(voter)
                if (votedFor == uuid.toString()) votes.set(voter, null)
            }
        }
        save()
    }

    override fun setCandidateStepdown(termIndex: Int, uuid: UUID) {
        yaml.set("terms.$termIndex.candidates.${uuid}.status", CandidateStatus.REMOVED.name)
        yaml.set("terms.$termIndex.candidates.${uuid}.stepdown", true)

        val votes = yaml.getConfigurationSection("terms.$termIndex.votes") ?: run { save(); return }
        votes.getKeys(false).forEach { voter ->
            val votedFor = votes.getString(voter)
            if (votedFor == uuid.toString()) votes.set(voter, null)
        }
        save()
    }

    override fun candidateSteppedDown(termIndex: Int, uuid: UUID): Boolean =
        yaml.getBoolean("terms.$termIndex.candidates.${uuid}.stepdown", false)

    override fun isPerksLocked(termIndex: Int, candidate: UUID): Boolean =
        yaml.getBoolean("terms.$termIndex.candidates.${candidate}.perks_locked", false)

    override fun setPerksLocked(termIndex: Int, candidate: UUID, locked: Boolean) {
        yaml.set("terms.$termIndex.candidates.${candidate}.perks_locked", locked)
        save()
    }

    // ------------------------------------------------------------------------
    // Voting
    // ------------------------------------------------------------------------

    override fun hasVoted(termIndex: Int, voter: UUID): Boolean =
        yaml.contains("terms.$termIndex.votes.${voter}")

    override fun votedFor(termIndex: Int, voter: UUID): UUID? {
        val raw = yaml.getString("terms.$termIndex.votes.${voter}") ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    override fun vote(termIndex: Int, voter: UUID, candidate: UUID) {
        yaml.set("terms.$termIndex.votes.${voter}", candidate.toString())
        save()
    }

    override fun voteCounts(termIndex: Int): Map<UUID, Int> {
        val sec = yaml.getConfigurationSection("terms.$termIndex.votes") ?: return emptyMap()
        val counts = mutableMapOf<UUID, Int>()
        sec.getKeys(false).forEach { voter ->
            val cand = sec.getString(voter)?.let(UUID::fromString) ?: return@forEach
            counts[cand] = (counts[cand] ?: 0) + 1
        }
        return counts
    }

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

    override fun chosenPerks(termIndex: Int, candidate: UUID): Set<String> {
        val list = yaml.getStringList("terms.$termIndex.candidates.${candidate}.perks")
        return list.toSet()
    }

    override fun setChosenPerks(termIndex: Int, candidate: UUID, perks: Set<String>) {
        yaml.set("terms.$termIndex.candidates.${candidate}.perks", perks.toList())
        save()
    }

    // ------------------------------------------------------------------------
    // Custom perk requests
    // ------------------------------------------------------------------------

    private fun nextRequestId(termIndex: Int): Int =
        (yaml.getInt("terms.$termIndex.requests.next_id", 1)).also {
            yaml.set("terms.$termIndex.requests.next_id", it + 1)
            save()
        }

    override fun addRequest(termIndex: Int, candidate: UUID, title: String, description: String): Int {
        val id = nextRequestId(termIndex)
        val path = "terms.$termIndex.requests.items.$id"
        yaml.set("$path.candidate", candidate.toString())
        yaml.set("$path.title", title)
        yaml.set("$path.description", description)
        yaml.set("$path.status", RequestStatus.PENDING.name)
        yaml.set("$path.createdAt", OffsetDateTime.now().toString())
        save()
        return id
    }

    override fun listRequests(termIndex: Int, status: RequestStatus?): List<CustomPerkRequest> {
        val sec = yaml.getConfigurationSection("terms.$termIndex.requests.items") ?: return emptyList()
        return sec.getKeys(false).mapNotNull { idStr ->
            val id = idStr.toIntOrNull() ?: return@mapNotNull null
            val cand = sec.getString("$idStr.candidate")?.let(UUID::fromString) ?: return@mapNotNull null
            val st = runCatching {
                RequestStatus.valueOf(sec.getString("$idStr.status") ?: RequestStatus.PENDING.name)
            }.getOrElse { RequestStatus.PENDING }
            if (status != null && st != status) return@mapNotNull null

            CustomPerkRequest(
                id = id,
                candidate = cand,
                title = sec.getString("$idStr.title") ?: "Untitled",
                description = sec.getString("$idStr.description") ?: "",
                status = st,
                createdAt = runCatching { OffsetDateTime.parse(sec.getString("$idStr.createdAt")) }
                    .getOrElse { OffsetDateTime.now() },
                onStart = sec.getStringList("$idStr.onStart"),
                onEnd = sec.getStringList("$idStr.onEnd")
            )
        }.sortedBy { it.id }
    }

    override fun setRequestStatus(termIndex: Int, requestId: Int, status: RequestStatus) {
        val base = "terms.$termIndex.requests.items.$requestId"
        yaml.set("$base.status", status.name)

        if (status != RequestStatus.APPROVED) {
            val candStr = yaml.getString("$base.candidate")
            val candidate = candStr?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (candidate != null) {
                val perksPath = "terms.$termIndex.candidates.$candidate.perks"
                val perks = yaml.getStringList(perksPath).toMutableList()
                val customId = "custom:$requestId"
                if (perks.removeIf { it == customId }) {
                    yaml.set(perksPath, perks)
                }
            }
        }

        save()
    }

    override fun setRequestCommands(termIndex: Int, requestId: Int, onStart: List<String>, onEnd: List<String>) {
        val base = "terms.$termIndex.requests.items.$requestId"
        yaml.set("$base.onStart", onStart)
        yaml.set("$base.onEnd", onEnd)
        save()
    }

    override fun requestCountForCandidate(term: Int, candidate: UUID): Int =
        listRequests(term).count { it.candidate == candidate }

    override fun clearRequests(termIndex: Int) {
        yaml.set("terms.$termIndex.requests.items", null)
        yaml.set("terms.$termIndex.requests.next_id", 1)
        save()
    }

    override fun clearUnapprovedRequests(termIndex: Int) {
        val sec = yaml.getConfigurationSection("terms.$termIndex.requests.items") ?: return
        for (idStr in sec.getKeys(false)) {
            val status = runCatching {
                RequestStatus.valueOf(sec.getString("$idStr.status") ?: RequestStatus.PENDING.name)
            }.getOrElse { RequestStatus.PENDING }
            if (status != RequestStatus.APPROVED) {
                sec.set(idStr, null)
            }
        }
        save()
    }

    // ------------------------------------------------------------------------
    // Apply bans (global)
    // ------------------------------------------------------------------------

    override fun activeApplyBan(uuid: UUID): ApplyBan? = ioLock.withLock {
        val base = "apply_bans.${uuid}"
        if (!yaml.contains(base)) return@withLock null

        val permanent = yaml.getBoolean("$base.permanent", false)
        val name = yaml.getString("$base.name") ?: "Unknown"
        val createdAt = runCatching {
            OffsetDateTime.parse(yaml.getString("$base.created_at") ?: OffsetDateTime.now().toString())
        }.getOrElse { OffsetDateTime.now() }
        val untilStr = yaml.getString("$base.until")
        val until = untilStr?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

        if (permanent) {
            return@withLock ApplyBan(uuid = uuid, lastKnownName = name, permanent = true, until = null, createdAt = createdAt)
        }

        if (until == null) {
            yaml.set(base, null)
            save()
            return@withLock null
        }

        val now = OffsetDateTime.now()
        return@withLock if (now.isBefore(until)) {
            ApplyBan(uuid = uuid, lastKnownName = name, permanent = false, until = until, createdAt = createdAt)
        } else {
            yaml.set(base, null)
            save()
            null
        }
    }

    override fun setApplyBanPermanent(uuid: UUID, lastKnownName: String) = ioLock.withLock {
        val base = "apply_bans.${uuid}"
        yaml.set("$base.name", lastKnownName)
        yaml.set("$base.permanent", true)
        yaml.set("$base.until", null)
        if (!yaml.contains("$base.created_at")) {
            yaml.set("$base.created_at", OffsetDateTime.now().toString())
        }
        save()
    }

    override fun setApplyBanTemp(uuid: UUID, lastKnownName: String, until: OffsetDateTime) = ioLock.withLock {
        val base = "apply_bans.${uuid}"
        yaml.set("$base.name", lastKnownName)
        yaml.set("$base.permanent", false)
        yaml.set("$base.until", until.toString())
        if (!yaml.contains("$base.created_at")) {
            yaml.set("$base.created_at", OffsetDateTime.now().toString())
        }
        save()
    }

    override fun clearApplyBan(uuid: UUID) = ioLock.withLock {
        val base = "apply_bans.${uuid}"
        if (!yaml.contains(base)) return@withLock
        yaml.set(base, null)
        save()
    }

    override fun listApplyBans(): List<ApplyBan> = ioLock.withLock {
        val sec = yaml.getConfigurationSection("apply_bans") ?: return@withLock emptyList()
        return@withLock sec.getKeys(false).mapNotNull { key ->
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@mapNotNull null
            val base = "apply_bans.$key"
            val permanent = yaml.getBoolean("$base.permanent", false)
            val name = yaml.getString("$base.name") ?: "Unknown"
            val createdAt = runCatching {
                OffsetDateTime.parse(yaml.getString("$base.created_at") ?: OffsetDateTime.now().toString())
            }.getOrElse { OffsetDateTime.now() }
            val untilStr = yaml.getString("$base.until")
            val until = untilStr?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ApplyBan(
                uuid = uuid,
                lastKnownName = name,
                permanent = permanent,
                until = if (permanent) null else until,
                createdAt = createdAt
            )
        }.sortedBy { it.lastKnownName.lowercase() }
    }

    override fun resetTermData() {
        ioLock.withLock {
            yaml.set("terms", null)
            everMayors.clear()
        }
        save()
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    override fun candidateEntry(termIndex: Int, uuid: UUID): CandidateEntry? = ioLock.withLock {
        val base = "terms.$termIndex.candidates.${uuid}"
        if (!yaml.contains(base)) return@withLock null
        val name = yaml.getString("$base.name") ?: "Unknown"
        val status = CandidateStatus.valueOf(yaml.getString("$base.status") ?: CandidateStatus.ACTIVE.name)
        CandidateEntry(uuid = uuid, lastKnownName = name, status = status)
    }
}
