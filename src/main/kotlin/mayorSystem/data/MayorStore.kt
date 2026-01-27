package mayorSystem.data

import mayorSystem.MayorPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.random.Random
import mayorSystem.config.TiePolicy
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tiny YAML-backed store.
 *
 * Data layout (elections.yml):
 * terms.<termIndex>.winner: <uuid>
 * terms.<termIndex>.winner_name: <string>
 * terms.<termIndex>.candidates.<uuid>.name: <string>
 * terms.<termIndex>.candidates.<uuid>.status: ACTIVE|PROCESS|REMOVED
 * terms.<termIndex>.candidates.<uuid>.applied_at: <instant>
 * terms.<termIndex>.candidates.<uuid>.bio: <string>
 * terms.<termIndex>.candidates.<uuid>.perks: perkId...
 * terms.<termIndex>.candidates.<uuid>.perks_locked: true/false
 * terms.<termIndex>.votes.<voterUuid>: <candidateUuid>
 * terms.<termIndex>.requests.next_id: <int>
 * terms.<termIndex>.requests.items.<id>.*: request fields
 * terms.<termIndex>.flags.election_open_announced: true/false
 */
class MayorStore(private val plugin: MayorPlugin) {

    private val file = File(plugin.dataFolder, "elections.yml")
    private val yaml: YamlConfiguration = YamlConfiguration.loadConfiguration(file)

    /**
     * Store writes are protected by a lock and written atomically to reduce
     * the odds of corruption if the server crashes mid-write.
     */
    private val ioLock = ReentrantLock()

    private fun save() = ioLock.withLock {
        if (!file.parentFile.exists()) file.parentFile.mkdirs()

        // Write to a temp file first, then move it over the real file.
        val tmp = File(file.parentFile, file.name + ".tmp")
        yaml.save(tmp)

        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Throwable) {
            // ATOMIC_MOVE isn't supported on every FS; fall back to a normal move.
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Cached set of UUIDs that have won at least one term.
     *
     * Why cache?
     * - elections.yml can grow over time on big servers.
     * - we don't want to scan the whole file every time an admin toggles a setting
     *   or a menu checks request permissions.
     */
    private val everMayors: MutableSet<UUID> = mutableSetOf<UUID>().also { set ->
        val terms = yaml.getConfigurationSection("terms") ?: return@also
        for (termKey in terms.getKeys(false)) {
            val win = yaml.getString("terms.$termKey.winner") ?: continue
            runCatching { UUID.fromString(win) }.getOrNull()?.let(set::add)
        }
    }

    /** True if the player has been mayor at least once (historically). */
    fun hasEverBeenMayor(uuid: UUID): Boolean = everMayors.contains(uuid)


    // ------------------------------------------------------------------------
    // Winner
    // ------------------------------------------------------------------------

    fun winner(termIndex: Int): UUID? =
        yaml.getString("terms.$termIndex.winner")?.let(UUID::fromString)

    fun winnerName(termIndex: Int): String? =
        yaml.getString("terms.$termIndex.winner_name")

    fun setWinner(termIndex: Int, uuid: UUID, lastKnownName: String) {
        yaml.set("terms.$termIndex.winner", uuid.toString())
        yaml.set("terms.$termIndex.winner_name", lastKnownName)
        save()
    }

    // ------------------------------------------------------------------------
    // Term flags
    // ------------------------------------------------------------------------

    fun electionOpenAnnounced(termIndex: Int): Boolean =
        yaml.getBoolean("terms.$termIndex.flags.election_open_announced", false)

    fun setElectionOpenAnnounced(termIndex: Int, value: Boolean) {
        yaml.set("terms.$termIndex.flags.election_open_announced", value)
        save()
    }

    // ------------------------------------------------------------------------
    // Candidates
    // ------------------------------------------------------------------------

    fun candidates(termIndex: Int, includeRemoved: Boolean): List<CandidateEntry> {
        val sec = yaml.getConfigurationSection("terms.$termIndex.candidates") ?: return emptyList()
        return sec.getKeys(false).mapNotNull { k ->
            val uuid = runCatching { UUID.fromString(k) }.getOrNull() ?: return@mapNotNull null
            val name = sec.getString("$k.name") ?: "Unknown"
            val status = runCatching {
                CandidateStatus.valueOf(sec.getString("$k.status") ?: CandidateStatus.ACTIVE.name)
            }.getOrElse { CandidateStatus.ACTIVE }

            // When includeRemoved = false, we still show PROCESS candidates (they're "in review")
            if (!includeRemoved && status == CandidateStatus.REMOVED) return@mapNotNull null
            CandidateEntry(uuid, name, status)
        }
    }

    fun isCandidate(termIndex: Int, uuid: UUID): Boolean =
        yaml.contains("terms.$termIndex.candidates.${uuid}")

    fun setCandidate(termIndex: Int, uuid: UUID, name: String) {
        yaml.set("terms.$termIndex.candidates.${uuid}.name", name)
        yaml.set("terms.$termIndex.candidates.${uuid}.status", CandidateStatus.ACTIVE.name)

        // Track when they first applied for deterministic tie-breaking and audits.
        val appliedAtPath = "terms.$termIndex.candidates.${uuid}.applied_at"
        if (!yaml.contains(appliedAtPath)) {
            yaml.set(appliedAtPath, Instant.now().toString())
        }

        // Candidate bio is optional; default empty.
        val bioPath = "terms.$termIndex.candidates.${uuid}.bio"
        if (!yaml.contains(bioPath)) {
            yaml.set(bioPath, "")
        }

        // perks_locked defaults to false (they can still pick until they confirm apply).
        if (!yaml.contains("terms.$termIndex.candidates.${uuid}.perks_locked")) {
            yaml.set("terms.$termIndex.candidates.${uuid}.perks_locked", false)
        }
        save()
    }

    fun candidateBio(termIndex: Int, candidate: UUID): String =
        yaml.getString("terms.$termIndex.candidates.${candidate}.bio") ?: ""

    fun setCandidateBio(termIndex: Int, candidate: UUID, bio: String) {
        yaml.set("terms.$termIndex.candidates.${candidate}.bio", bio)
        save()
    }

    fun candidateAppliedAt(termIndex: Int, candidate: UUID): Instant? {
        val raw = yaml.getString("terms.$termIndex.candidates.${candidate}.applied_at") ?: return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    fun setCandidateStatus(termIndex: Int, uuid: UUID, status: CandidateStatus) {
        yaml.set("terms.$termIndex.candidates.${uuid}.status", status.name)

        // If fully REMOVED -> refund votes for voters who picked them.
        if (status == CandidateStatus.REMOVED) {
            val votes = yaml.getConfigurationSection("terms.$termIndex.votes") ?: run { save(); return }
            votes.getKeys(false).forEach { voter ->
                val votedFor = votes.getString(voter)
                if (votedFor == uuid.toString()) votes.set(voter, null)
            }
        }
        save()
    }

    fun isPerksLocked(termIndex: Int, candidate: UUID): Boolean =
        yaml.getBoolean("terms.$termIndex.candidates.${candidate}.perks_locked", false)

    fun setPerksLocked(termIndex: Int, candidate: UUID, locked: Boolean) {
        yaml.set("terms.$termIndex.candidates.${candidate}.perks_locked", locked)
        save()
    }

    // ------------------------------------------------------------------------
    // Voting
    // ------------------------------------------------------------------------

    fun hasVoted(termIndex: Int, voter: UUID): Boolean =
        yaml.contains("terms.$termIndex.votes.${voter}")

    fun votedFor(termIndex: Int, voter: UUID): UUID? {
        val raw = yaml.getString("terms.$termIndex.votes.${voter}") ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    fun vote(termIndex: Int, voter: UUID, candidate: UUID) {
        yaml.set("terms.$termIndex.votes.${voter}", candidate.toString())
        save()
    }

    fun voteCounts(termIndex: Int): Map<UUID, Int> {
        val sec = yaml.getConfigurationSection("terms.$termIndex.votes") ?: return emptyMap()
        val counts = mutableMapOf<UUID, Int>()
        sec.getKeys(false).forEach { voter ->
            val cand = sec.getString(voter)?.let(UUID::fromString) ?: return@forEach
            counts[cand] = (counts[cand] ?: 0) + 1
        }
        return counts
    }

    /**
     * Picks a winner from the highest vote count.
     * - Only ACTIVE candidates are eligible.
     * - PROCESS candidates are shown publicly but are NOT electable.
     * - REMOVED candidates are ignored.
     */
    fun pickWinner(
        termIndex: Int,
        tiePolicy: TiePolicy,
        incumbent: UUID? = null,
        seededRngSeed: Long = termIndex.toLong(),
        logDecision: ((String) -> Unit)? = null
    ): CandidateEntry? {
        val eligible = candidates(termIndex, includeRemoved = false)
            .filter { it.status == CandidateStatus.ACTIVE }

        if (eligible.isEmpty()) return null

        val counts = voteCounts(termIndex)
        val max = eligible.maxOf { counts[it.uuid] ?: 0 }

        // No votes yet -> still pick someone (tie among all eligible).
        // Important for admin "end votes now" flows.
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
                // If all were EPOCH (missing), this still gives a deterministic order.
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

    fun topCandidates(term: Int, limit: Int = 3, includeRemoved: Boolean = false): List<Pair<CandidateEntry, Int>> {
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

    fun chosenPerks(termIndex: Int, candidate: UUID): Set<String> {
        val list = yaml.getStringList("terms.$termIndex.candidates.${candidate}.perks")
        return list.toSet()
    }

    fun setChosenPerks(termIndex: Int, candidate: UUID, perks: Set<String>) {
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

    fun addRequest(termIndex: Int, candidate: UUID, title: String, description: String): Int {
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

    fun listRequests(termIndex: Int, status: RequestStatus? = null): List<CustomPerkRequest> {
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

    fun setRequestStatus(termIndex: Int, requestId: Int, status: RequestStatus) {
        val base = "terms.$termIndex.requests.items.$requestId"
        yaml.set("$base.status", status.name)

        // If a request is no longer approved, it must not stay selected as a perk.
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

    fun setRequestCommands(termIndex: Int, requestId: Int, onStart: List<String>, onEnd: List<String>) {
        val base = "terms.$termIndex.requests.items.$requestId"
        yaml.set("$base.onStart", onStart)
        yaml.set("$base.onEnd", onEnd)
        save()
    }

    fun requestCountForCandidate(term: Int, candidate: UUID): Int =
        listRequests(term).count { it.candidate == candidate }

    fun clearRequests(termIndex: Int) {
        yaml.set("terms.$termIndex.requests.items", null)
        yaml.set("terms.$termIndex.requests.next_id", 1)
        save()
    }

    /**
     * Removes all custom requests that are NOT approved for a term.
     * Approved requests are preserved for reference.
     */
    fun clearUnapprovedRequests(termIndex: Int) {
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

    /**
     * Returns the current apply ban if active, otherwise null.
     *
     * Temp bans expire automatically and are cleared when checked.
     */
    fun activeApplyBan(uuid: UUID): ApplyBan? = ioLock.withLock {
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
            // Corrupt entry -> clear it so we don't trap people forever.
            yaml.set(base, null)
            save()
            return@withLock null
        }

        val now = OffsetDateTime.now()
        return@withLock if (now.isBefore(until)) {
            ApplyBan(uuid = uuid, lastKnownName = name, permanent = false, until = until, createdAt = createdAt)
        } else {
            // Expired -> clear
            yaml.set(base, null)
            save()
            null
        }
    }

    fun setApplyBanPermanent(uuid: UUID, lastKnownName: String) = ioLock.withLock {
        val base = "apply_bans.${uuid}"
        yaml.set("$base.name", lastKnownName)
        yaml.set("$base.permanent", true)
        yaml.set("$base.until", null)
        if (!yaml.contains("$base.created_at")) {
            yaml.set("$base.created_at", OffsetDateTime.now().toString())
        }
        save()
    }

    fun setApplyBanTemp(uuid: UUID, lastKnownName: String, until: OffsetDateTime) = ioLock.withLock {
        val base = "apply_bans.${uuid}"
        yaml.set("$base.name", lastKnownName)
        yaml.set("$base.permanent", false)
        yaml.set("$base.until", until.toString())
        if (!yaml.contains("$base.created_at")) {
            yaml.set("$base.created_at", OffsetDateTime.now().toString())
        }
        save()
    }

    fun clearApplyBan(uuid: UUID) = ioLock.withLock {
        val base = "apply_bans.${uuid}"
        if (!yaml.contains(base)) return@withLock
        yaml.set(base, null)
        save()
    }

    fun listApplyBans(): List<ApplyBan> = ioLock.withLock {
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

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    fun candidateEntry(termIndex: Int, uuid: UUID): CandidateEntry? = ioLock.withLock {
        val base = "terms.$termIndex.candidates.${uuid}"
        if (!yaml.contains(base)) return@withLock null
        val name = yaml.getString("$base.name") ?: "Unknown"
        val status = CandidateStatus.valueOf(yaml.getString("$base.status") ?: CandidateStatus.ACTIVE.name)
        CandidateEntry(uuid = uuid, lastKnownName = name, status = status)
    }

}
