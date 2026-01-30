package mayorSystem.service

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.RequestStatus
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import mayorSystem.ux.MayorBroadcasts
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.lang.reflect.Method
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.logging.Level

data class TermTimes(
    val termStart: Instant,
    val termEnd: Instant,
    val electionOpen: Instant,
    val electionClose: Instant
)

/**
 * Term timeline + election finalization.
 *
 * Core idea:
 * - Terms normally begin at settings.firstTermStart, then repeat every settings.termLength.
 * - Admins can *shift the schedule* by setting term start overrides for specific terms:
 *   admin.term_start_override.<termIndex> = ISO-8601 instant string
 *   When an override exists for term K, all terms >= K follow from that start time by termLength steps,
 *   until another override appears.
 *
 * - Admins can also "hard override" election open/closed:
 *   admin.election_override.<termIndex> = OPEN/CLOSED
 *
 * The goal is: no matter what admins do, every component reads from the same source of truth.
 */
class TermService(private val plugin: MayorPlugin) {
    private val plain = PlainTextComponentSerializer.plainText()

    /**
     * Hard guardrail: any admin action or scheduled tick that mutates
     * election/term state must run "one at a time".
     *
     * Why: admins can spam-click menu buttons, multiple staff can use menus
     * at the same time, and the scheduled tick can fire mid-action.
     * This lock prevents race-y double-finalizes or partially written state.
     */
    private val stateLock = ReentrantLock()

    private inline fun <T> locked(block: () -> T): T = stateLock.withLock(block)

    // ---------------------------------------------------------------------
    // Election-open broadcast (optional UX)
    // ---------------------------------------------------------------------

    private enum class BroadcastMode { CHAT, TITLE, BOTH }

    /**
     * Deserialize a legacy-formatted string into an Adventure Component.
     * Supports both '&' and '§' codes.
     */
    fun deserializeLegacy(raw: String) = MayorBroadcasts.deserialize(raw)

    // Reflection-only support for PlaceholderAPI (optional dependency)
    private val papiSetPlaceholders: Method? = runCatching {
        val cls = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
        cls.getMethod("setPlaceholders", Player::class.java, String::class.java)
    }.getOrNull()

    private fun applyPlaceholders(p: Player, raw: String): String {
        val m = papiSetPlaceholders ?: return raw
        return runCatching { m.invoke(null, p, raw) as? String }.getOrNull() ?: raw
    }

    private fun replaceBuiltins(raw: String, termHuman: Int, mayorName: String? = null): String {
        return raw
            .replace("%term%", termHuman.toString())
            .replace("%mayor_name%", mayorName ?: "None")
    }

    private fun broadcastMode(): BroadcastMode {
        val raw = plugin.config.getString("election.broadcast.mode", "TITLE") ?: "TITLE"
        return when (raw.uppercase()) {
            "CHAT" -> BroadcastMode.CHAT
            "BOTH" -> BroadcastMode.BOTH
            else -> BroadcastMode.TITLE
        }
    }

    private fun maybeBroadcastElectionOpen(now: Instant, electionTermIndex: Int) {
        if (electionTermIndex < 0) return
        if (!plugin.config.getBoolean("election.broadcast.enabled", true)) return
        if (!isElectionOpen(now, electionTermIndex)) return
        if (plugin.store.electionOpenAnnounced(electionTermIndex)) return

        // Mark first so if something downstream throws, we still avoid spamming every tick.
        plugin.store.setElectionOpenAnnounced(electionTermIndex, true)

        val termHuman = electionTermIndex + 1
        val mode = broadcastMode()

        val defaultChat = listOf(
            "&e&lElections are now OPEN! &7(Term #%term%)",
            "&7Vote now: &e/mayor vote &7or open the Mayor menu."
        )

        val chatLines = plugin.config.getStringList("election.broadcast.open.chat_lines").ifEmpty { defaultChat }
        val titleRawCfg = plugin.config.getString("election.broadcast.open.title", "&e&lElections Open!")
            ?: "&e&lElections Open!"
        val subRawCfg = plugin.config.getString("election.broadcast.open.subtitle", "&7Vote now with &e/mayor")
            ?: "&7Vote now with &e/mayor"
        val fadeInMs = plugin.config.getLong("election.broadcast.title.fade_in_ms", 500L)
        val stayMs = plugin.config.getLong("election.broadcast.title.stay_ms", 4000L)
        val fadeOutMs = plugin.config.getLong("election.broadcast.title.fade_out_ms", 800L)

        val sendChat = {
            MayorBroadcasts.broadcastChat(chatLines) { p, raw ->
                // Built-ins first; PlaceholderAPI is applied inside MayorBroadcasts if installed.
                replaceBuiltins(raw, termHuman)
            }
        }

        val sendTitle = {
            Bukkit.getOnlinePlayers().forEach { p ->
                val titleBuilt = replaceBuiltins(titleRawCfg, termHuman)
                val subBuilt = replaceBuiltins(subRawCfg, termHuman)
                val title = deserializeLegacy(applyPlaceholders(p, titleBuilt))
                val sub = deserializeLegacy(applyPlaceholders(p, subBuilt))
                val t = Title.title(
                    title,
                    sub,
                    Title.Times.times(
                        Duration.ofMillis(fadeInMs),
                        Duration.ofMillis(stayMs),
                        Duration.ofMillis(fadeOutMs)
                    )
                )
                p.showTitle(t)
            }
        }

        when (mode) {
            BroadcastMode.CHAT -> sendChat()
            BroadcastMode.TITLE -> sendTitle()
            BroadcastMode.BOTH -> {
                sendChat()
                sendTitle()
            }
        }
    }

    private fun maybeBroadcastMayorElected(termIndex: Int, mayorUuid: UUID, mayorName: String) {
        if (termIndex < 0) return
        if (!plugin.config.getBoolean("election.broadcast.enabled", true)) return
        if (plugin.store.mayorElectedAnnounced(termIndex)) return

        plugin.store.setMayorElectedAnnounced(termIndex, true)

        val termHuman = termIndex + 1
        val mode = broadcastMode()

        val defaultChat = listOf(
            "&a&lNew Mayor elected! &7(Term #%term%)",
            "&7Mayor: &e%mayor_name%"
        )

        val baseChatLines = plugin.config.getStringList("election.broadcast.elected.chat_lines").ifEmpty { defaultChat }
        val perkLine = buildPerkSummaryLine(termIndex, mayorUuid, baseChatLines)
        val chatLines = if (perkLine == null) baseChatLines else baseChatLines + perkLine
        val titleRawCfg = plugin.config.getString("election.broadcast.elected.title", "&a&lNew Mayor!")
            ?: "&a&lNew Mayor!"
        val subRawCfg = plugin.config.getString("election.broadcast.elected.subtitle", "&e%mayor_name% &7(Term #%term%)")
            ?: "&e%mayor_name% &7(Term #%term%)"
        val fadeInMs = plugin.config.getLong("election.broadcast.title.fade_in_ms", 500L)
        val stayMs = plugin.config.getLong("election.broadcast.title.stay_ms", 4000L)
        val fadeOutMs = plugin.config.getLong("election.broadcast.title.fade_out_ms", 800L)

        val sendChat = {
            MayorBroadcasts.broadcastChat(chatLines) { _, raw ->
                replaceBuiltins(raw, termHuman, mayorName)
            }
        }

        val sendTitle = {
            Bukkit.getOnlinePlayers().forEach { p ->
                val titleBuilt = replaceBuiltins(titleRawCfg, termHuman, mayorName)
                val subBuilt = replaceBuiltins(subRawCfg, termHuman, mayorName)
                val title = deserializeLegacy(applyPlaceholders(p, titleBuilt))
                val sub = deserializeLegacy(applyPlaceholders(p, subBuilt))
                val t = Title.title(
                    title,
                    sub,
                    Title.Times.times(
                        Duration.ofMillis(fadeInMs),
                        Duration.ofMillis(stayMs),
                        Duration.ofMillis(fadeOutMs)
                    )
                )
                p.showTitle(t)
            }
        }

        when (mode) {
            BroadcastMode.CHAT -> sendChat()
            BroadcastMode.TITLE -> sendTitle()
            BroadcastMode.BOTH -> {
                sendChat()
                sendTitle()
            }
        }
    }

    private fun buildPerkSummaryLine(termIndex: Int, mayorUuid: UUID, chatLines: List<String>): String? {
        val chosen = plugin.store.chosenPerks(termIndex, mayorUuid)
        if (chosen.isEmpty()) return null

        val displayNames = chosen.map { plugin.perks.displayNameFor(termIndex, it) }
            .map { plain.serialize(MayorBroadcasts.deserialize(it)).trim() }
            .filter { it.isNotBlank() }

        if (displayNames.isEmpty()) return null

        val mayorColor = extractMayorNameColor(chatLines) ?: "&e"
        return "&7Perks: $mayorColor${displayNames.joinToString(", ")}"
    }

    private fun extractMayorNameColor(chatLines: List<String>): String? {
        val placeholder = "%mayor_name%"
        val legacyRegex = Regex("(?i)(?:&|§)[0-9a-f]")
        val line = chatLines.firstOrNull { it.contains(placeholder) } ?: return null
        val idx = line.indexOf(placeholder)
        if (idx <= 0) return null
        val prefix = line.substring(0, idx)
        val matches = legacyRegex.findAll(prefix).toList()
        val last = matches.lastOrNull() ?: return null
        val code = last.value.last().lowercase()
        return "&$code"
    }

    // -------------------------------------------------------------------------
    // Schedule / timeline
    // -------------------------------------------------------------------------

    /**
     * Compute (currentTermIndex, electionTermIndex) from "now".
     * - currentTermIndex starts at 0 for term #1, -1 if first term hasn't started yet
     * - electionTermIndex is always currentTermIndex + 1
     *
     * IMPORTANT: This respects admin term_start_override shifts.
     */
    fun compute(now: Instant): Pair<Int, Int> {
        val effectiveNow = effectiveNow(now)
        val anchor0 = termStart(0)

        // before term #1 begins
        if (effectiveNow.isBefore(anchor0)) return -1 to 0

        val termLen = plugin.settings.termLength

        // We treat the schedule as segments. Each segment is defined by an anchor:
        // (termIndex -> startInstant). The base anchor is term 0.
        // Admin overrides introduce new anchors.
        val anchors = buildAnchors()

        // Find the active segment for "now".
        // Walk anchors in order: if there's a next anchor and now >= next.start, move forward.
        var activeAnchorIndex = 0
        while (activeAnchorIndex + 1 < anchors.size && !effectiveNow.isBefore(anchors[activeAnchorIndex + 1].second)) {
            activeAnchorIndex++
        }

        val (segmentTermIndex, segmentStart) = anchors[activeAnchorIndex]
        val elapsedMs = Duration.between(segmentStart, effectiveNow).toMillis().coerceAtLeast(0)
        val steps = (elapsedMs / termLen.toMillis()).toInt()
        val current = segmentTermIndex + steps

        return current to (current + 1)
    }

    private var cachedComputeAtMs: Long = 0L
    private var cachedComputeResult: Pair<Int, Int>? = null

    fun computeCached(now: Instant): Pair<Int, Int> {
        val nowMs = System.currentTimeMillis()
        val cached = cachedComputeResult
        if (cached != null && nowMs - cachedComputeAtMs < 1000L) {
            return cached
        }
        val result = compute(now)
        cachedComputeAtMs = nowMs
        cachedComputeResult = result
        return result
    }

    fun computeNow(): Pair<Int, Int> = computeCached(Instant.now())

    fun invalidateScheduleCache() {
        cachedComputeAtMs = 0L
        cachedComputeResult = null
    }

    /**
     * Compute term timeline for a specific term index (0-based).
     * IMPORTANT: respects term_start_override schedule shifts.
     */
    fun timesFor(termIndex: Int): TermTimes {
        val termLen = plugin.settings.termLength
        val termStart = termStart(termIndex)
        val termEnd = termStart.plus(termLen)
        val electionClose = termStart
        val electionOpen = electionClose.minus(plugin.settings.voteWindow)
        return TermTimes(termStart, termEnd, electionOpen, electionClose)
    }

    /**
     * Timeline-based election window + admin open/closed override.
     *
     * This is the *single* method every page/command should use.
     */
    fun isElectionOpen(now: Instant, electionTermIndex: Int): Boolean {
        return when (getElectionOverride(electionTermIndex)) {
            "OPEN" -> true
            "CLOSED" -> false
            else -> {
                val t = timesFor(electionTermIndex)
                val effectiveNow = effectiveNow(now)
                !effectiveNow.isBefore(t.electionOpen) && effectiveNow.isBefore(t.electionClose)
            }
        }
    }

    private fun effectiveNow(now: Instant): Instant {
        val totalMs = plugin.config.getLong("admin.pause.total_ms", 0L).coerceAtLeast(0)
        val startedRaw = plugin.config.getString("admin.pause.started_at")
        val startedAt = startedRaw?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val pauseEnabled = plugin.settings.pauseEnabled

        if (pauseEnabled && startedAt == null) {
            plugin.config.set("admin.pause.started_at", now.toString())
            plugin.saveConfig()
            return now.minusMillis(totalMs)
        }

        if (!pauseEnabled && startedAt != null) {
            val deltaMs = Duration.between(startedAt, now).toMillis().coerceAtLeast(0)
            plugin.config.set("admin.pause.total_ms", totalMs + deltaMs)
            plugin.config.set("admin.pause.started_at", null)
            plugin.saveConfig()
            return now.minusMillis(totalMs + deltaMs)
        }

        val pausedMs = if (pauseEnabled && startedAt != null) Duration.between(startedAt, now).toMillis().coerceAtLeast(0) else 0
        return now.minusMillis(totalMs + pausedMs)
    }

    // -------------------------------------------------------------------------
    // Admin controls (instant actions)
    // -------------------------------------------------------------------------

    /**
     * Start the election NOW in a way that keeps a logical schedule:
     * - election opens now
     * - election closes at (now + voteWindow)
     * - next term starts at (now + voteWindow)
     *
     * This shifts the schedule earlier/later by creating an anchor at the upcoming term start.
     */
    fun forceStartElectionNow(): Boolean {
        if (!plugin.settings.enabled) return false

        return stateLock.withLock {
            val now = Instant.now()
            val electionTerm = compute(now).second
            if (electionTerm < 0) return@withLock false

            // Set term start so electionOpen == now (because electionOpen = termStart - voteWindow).
            val newStart = now.plus(plugin.settings.voteWindow)
            val validation = validateTermStartOverride(electionTerm, newStart)
            if (validation != null) {
                plugin.logger.warning("Rejected forceStartElectionNow: $validation")
                return@withLock false
            }
            setTermStartOverride(electionTerm, newStart)

            // Clear hard overrides to avoid confusing UI.
            clearElectionOverride(electionTerm)

            plugin.saveConfig()

            // Make sure the live state reflects the change immediately.
            // (no-op most of the time, but makes admin actions feel instant)
            tick()
            true
        }
    }

    /**
     * End the election NOW and start the new term instantly.
     *
     * This:
     * - finalizes the election for the upcoming term
     * - clears previous term perks + publishes winning custom perks + purges old custom requests
     * - elects winner (or forced mayor) and applies perks
     * - shifts schedule: upcoming term starts NOW
     */
    
    fun forceEndElectionNow(): Boolean {
        if (!plugin.settings.enabled) return false

        return stateLock.withLock {
            val now = Instant.now()
            val (currentTerm, electionTerm) = compute(now)
            if (electionTerm < 0) return@withLock false

            // Force the upcoming term to begin right now.
	            safeFinalizeElectionForTerm(
	                electionTerm = electionTerm,
	                currentTermAtCall = currentTerm,
	                forcedTermStart = now
	            )
            true
        }
    }

    /**
     * Force-elect a specific player and start the new term immediately.
     * This is the "big red button" for admins.
     */
    
    fun forceElectNow(uuid: UUID, name: String): Boolean {
        if (!plugin.settings.enabled) return false

        return stateLock.withLock {
            val now = Instant.now()
            val (currentTerm, electionTerm) = compute(now)
            if (electionTerm < 0) return@withLock false

            // Store forced mayor for audit / UI visibility (optional, but nice).
            plugin.config.set("admin.forced_mayor.$electionTerm.uuid", uuid.toString())
            plugin.config.set("admin.forced_mayor.$electionTerm.name", name)
            plugin.saveConfig()

            // Force-start the upcoming term right now.
	            safeFinalizeElectionForTerm(
	                electionTerm = electionTerm,
	                currentTermAtCall = currentTerm,
	                forcedTermStart = now
	            )
            true
        }
    }

    /**
     * Wipe every admin override related to a specific election term.
     *
     * This exists for the Admin UI “Clear overrides” button.
     *
     * What it clears:
     * - admin.election_override.<term>
     * - admin.forced_mayor.<term>
     * - admin.term_start_override.<term>
     */
    fun clearAllOverridesForTerm(term: Int) {
        if (term < 0) return
        stateLock.withLock {
            plugin.config.set("admin.election_override.$term", null)
            plugin.config.set("admin.forced_mayor.$term", null)
            plugin.config.set("admin.term_start_override.$term", null)
            plugin.saveConfig()
        }
    }

    // -------------------------------------------------------------------------
    // Main loop (scheduled tick)
    // -------------------------------------------------------------------------

    /**
     * Called on a repeating task.
     * - finalizes the election when the voting window closes (or admin forces CLOSE)
     * - clears previous term perks
     * - elects the next mayor (or admin-forced mayor)
     * - applies the new term perks
     * - after a term ends: publishes winning custom perk(s) into perks.sections.public_perks,
     *   and deletes any non-approved custom perk requests from that ended term.
     */
    fun tick() {
        if (!plugin.settings.enabled) return

        // Tick runs on a scheduler, but admins can also mutate elections via GUI.
        // Serialize all mutations so we never finalize twice or apply perks twice.
        stateLock.withLock {
            val now = Instant.now()
            val (currentTerm, nextElectionTerm) = compute(now)

            // Optional UX: announce once when elections become open.
            // Stored in elections.yml so it survives restart/reload without spamming.
            maybeBroadcastElectionOpen(now, nextElectionTerm)

            // -----------------------------------------------------------------
            // 1) Catch-up guardrail (startup + normal runtime)
            // -----------------------------------------------------------------
            // If the schedule says we're already inside a term but no winner was
            // ever recorded for that term (e.g., server was offline at term start),
            // we MUST finalize that term immediately so perks + mayor actually apply.
            if (currentTerm >= 0) {
                val needsStart = plugin.store.winner(currentTerm) == null
                if (needsStart) {
                    // Starting term K ends term K-1.
                    safeFinalizeElectionForTerm(
                        electionTerm = currentTerm,
                        currentTermAtCall = currentTerm - 1,
                        forcedTermStart = null
                    )
                    return
                }
            }

            // -----------------------------------------------------------------
            // 2) Admin “force close” on the upcoming election term
            // -----------------------------------------------------------------
            // Some admin UIs toggle an override to close the election early.
            // In that case, we interpret it as “start the next term now”
            // (same semantics as forceEndElectionNow).
            if (nextElectionTerm >= 0) {
                val forcedCloseNext = getElectionOverride(nextElectionTerm) == "CLOSED"
                val alreadyElectedNext = plugin.store.winner(nextElectionTerm) != null
                if (forcedCloseNext && !alreadyElectedNext) {
                    safeFinalizeElectionForTerm(
                        electionTerm = nextElectionTerm,
                        currentTermAtCall = currentTerm,
                        forcedTermStart = now
                    )
                    return
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internals: election finalization
    // -------------------------------------------------------------------------

    /**
     * Finalize the election for [electionTerm], and start that term at [startNow].
     *
     * [currentTermAtCall] should usually be compute(startNow).first *before* any overrides are changed.
     * (We pass it in so forced schedule shifts don't confuse which "previous term" we are ending.)
     */
    
    
    /**
     * Wrapper around [finalizeElectionForTerm] that guarantees we never explode the server thread
     * if a perk command or store read misbehaves.
     */
    private fun safeFinalizeElectionForTerm(
        electionTerm: Int,
        currentTermAtCall: Int,
        forcedTermStart: Instant?
    ) {
        try {
            finalizeElectionForTerm(
                electionTerm = electionTerm,
                currentTermAtCall = currentTermAtCall,
                forcedTermStart = forcedTermStart
            )
        } catch (t: Throwable) {
            plugin.logger.log(Level.SEVERE, "Failed to finalize election for term=$electionTerm", t)
        }
    }

	private fun finalizeElectionForTerm(
        electionTerm: Int,
        currentTermAtCall: Int,
        forcedTermStart: Instant?
    ) {
        val previousTerm = electionTerm - 1

        // 1) End the previous term (if any): clear perks + publish winning custom perks + purge requests
        if (previousTerm >= 0 && previousTerm == currentTermAtCall) {
            // Clear perks first so custom onEnd commands still exist before we purge requests.
            // Guardrail: NEVER let a perk crash the election pipeline.
            try {
                plugin.perks.clearPerks(previousTerm)
            } catch (t: Throwable) {
                plugin.logger.log(Level.SEVERE, "Failed to clear perks for term=$previousTerm", t)
            }

            // Publish winning custom perk(s) from the term that just ended, then wipe unapproved requests.
            publishWinnerCustomPerksToPublic(previousTerm)
            plugin.store.clearUnapprovedRequests(previousTerm)
        }

        // 2) Choose winner for the upcoming term
        val winnerEntry: CandidateEntry? =
            getForcedMayor(electionTerm)
                ?.let { forced ->
                    // Ensure they're present as a candidate entry so menus/records look sane.
                    plugin.store.setCandidate(electionTerm, forced.first, forced.second)
                    CandidateEntry(forced.first, forced.second, CandidateStatus.ACTIVE)
                }
                ?: run {
                    // Normal election: pick from votes (store applies eligibility rules).
                    val incumbent = if (previousTerm >= 0) plugin.store.winner(previousTerm) else null
                    plugin.store.pickWinner(
                        termIndex = electionTerm,
                        tiePolicy = plugin.settings.tiePolicy,
                        incumbent = incumbent,
                        seededRngSeed = (electionTerm.toLong() * 1000003L) xor (plugin.settings.firstTermStart.toEpochSecond()),
                        logDecision = { msg -> plugin.logger.info(msg) }
                    )
                }

        if (winnerEntry == null) {
            val termHuman = electionTerm + 1
            val raw = plugin.config.getString(
                "election.broadcast.no_candidates.chat",
                "&cNo candidates for term #%term%."
            ) ?: "&cNo candidates for term #%term%."
            // Keep chat announcements consistent (header/footer + PAPI if installed).
            MayorBroadcasts.broadcastChat(listOf(raw)) { _, line ->
                replaceBuiltins(line, termHuman)
            }
            clearElectionOverride(electionTerm)
            clearForcedMayor(electionTerm)
            plugin.saveConfig()
            return
        }

        // 3) Mark winner
        plugin.store.setWinner(electionTerm, winnerEntry.uuid, winnerEntry.lastKnownName)

        // 4) Start the new term:
        // - If forcedTermStart is provided, we shift the schedule to that instant.
        // - Otherwise we DO NOT touch the schedule (prevents drift on normal ticks).
        if (forcedTermStart != null) {
            setTermStartOverride(electionTerm, forcedTermStart)
        }

        // Public-perks publishing + schedule overrides + admin flags all live in config.yml,
        // so we persist once at the end.
        clearElectionOverride(electionTerm)
        clearForcedMayor(electionTerm)
        plugin.saveConfig()

        // Now that the winner is saved (elections.yml) AND the schedule shift (if any) is applied,
        // update the Mayor NPC using the *new* term index to avoid showing the old mayor.
        plugin.mayorNpc.forceUpdateMayorForTerm(electionTerm)

        // 3b) Announce (configurable, anti-spam per term)
        maybeBroadcastMayorElected(electionTerm, winnerEntry.uuid, winnerEntry.lastKnownName)

        // 5) Apply the new term perks
        // Apply immediately (either we're starting now, or the scheduled start already happened and the tick is catching up).
        try {
            val suppressSay = plugin.config.getBoolean("election.broadcast.enabled", true) &&
                broadcastMode() != BroadcastMode.TITLE
            plugin.perks.applyPerks(electionTerm, suppressSayBroadcast = suppressSay)
        } catch (t: Throwable) {
            plugin.logger.log(Level.SEVERE, "Failed to apply perks for term $electionTerm", t)
        }

        // One more refresh after perks apply, in case perks modify prefixes / display meta.
        plugin.mayorNpc.forceUpdateMayorForTerm(electionTerm)
    }

    // -------------------------------------------------------------------------
    // Admin config helpers
    // -------------------------------------------------------------------------

    private fun getElectionOverride(term: Int): String? =
        plugin.config.getString("admin.election_override.$term")?.uppercase()

    private fun clearElectionOverride(term: Int) {
        if (plugin.config.contains("admin.election_override.$term")) {
            plugin.config.set("admin.election_override.$term", null)
        }
    }

    private fun getForcedMayor(term: Int): Pair<UUID, String>? {
        val uuidStr = plugin.config.getString("admin.forced_mayor.$term.uuid") ?: return null
        val name = plugin.config.getString("admin.forced_mayor.$term.name") ?: "Unknown"
        val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: return null
        return uuid to name
    }

    private fun clearForcedMayor(term: Int) {
        if (plugin.config.contains("admin.forced_mayor.$term")) {
            plugin.config.set("admin.forced_mayor.$term", null)
        }
    }

    // -------------------------------------------------------------------------
    // Schedule overrides
    // -------------------------------------------------------------------------

    /**
     * Read "admin.term_start_override" section into a map.
     * Keys are term indices, values are Instants.
     */
    private fun termStartOverrides(): Map<Int, Instant> {
        val sec = plugin.config.getConfigurationSection("admin.term_start_override") ?: return emptyMap()
        val out = mutableMapOf<Int, Instant>()
        for (k in sec.getKeys(false)) {
            val idx = k.toIntOrNull() ?: continue
            val raw = sec.getString(k) ?: continue
            val inst = runCatching { Instant.parse(raw) }.getOrNull() ?: continue
            out[idx] = inst
        }
        return out
    }

    /**
     * Build sorted anchors: base (0, start0) + admin overrides (sorted).
     */
    private fun buildAnchors(): List<Pair<Int, Instant>> {
        val baseStart = plugin.settings.firstTermStart.toInstant()

        val overrides = termStartOverrides()
        val anchors = mutableListOf<Pair<Int, Instant>>()

        // base anchor is term 0, unless overridden directly
        anchors += (0 to (overrides[0] ?: baseStart))

        overrides.entries
            .filter { it.key != 0 }
            .sortedBy { it.key }
            .forEach { anchors += (it.key to it.value) }

        return anchors
    }

    /**
     * Compute the start instant for a term index using the most recent anchor <= termIndex.
     */
    private fun termStart(termIndex: Int): Instant {
        if (termIndex <= 0) {
            // term 0 anchor
            val baseStart = plugin.settings.firstTermStart.toInstant()
            val override0 = plugin.config.getString("admin.term_start_override.0")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            return override0 ?: baseStart
        }

        val termLen = plugin.settings.termLength
        val overrides = termStartOverrides()

        // Pick the latest override <= termIndex (excluding 0 handled above)
        val best = overrides.keys.filter { it in 1..termIndex }.maxOrNull()
        if (best == null) {
            val base = plugin.settings.firstTermStart.toInstant()
            return base.plus(termLen.multipliedBy(termIndex.toLong()))
        }

        val anchorStart = overrides[best]!!
        val deltaTerms = termIndex - best
        return anchorStart.plus(termLen.multipliedBy(deltaTerms.toLong()))
    }

    /**
     * Validate a term start override so an admin can't accidentally create
     * a "time travel" timeline that breaks term math.
     *
     * Rules (intentionally simple and strict):
     * - Only allow overrides for the current term or future terms.
     * - New start must be AFTER the start of the previous term.
     */
    private fun validateTermStartOverride(termIndex: Int, newStart: Instant): String? {
        if (termIndex < 0) return "Invalid term index."

        val now = Instant.now()
        val (currentTerm, _) = compute(now)

        // Don't allow rewriting history (it causes non-obvious state bugs).
        if (termIndex < currentTerm) {
            return "You can only change the current/upcoming term schedule (termIndex >= $currentTerm)."
        }

        if (termIndex > 0) {
            val prevStart = termStart(termIndex - 1)
            if (!newStart.isAfter(prevStart)) {
                return "Term start must be after the previous term start."
            }
        }

        return null
    }

    private fun setTermStartOverride(termIndex: Int, start: Instant) {
        plugin.config.set("admin.term_start_override.$termIndex", start.toString())
    }

    // -------------------------------------------------------------------------
    // Custom perk publishing
    // -------------------------------------------------------------------------

    private fun ensurePublicPerksSectionExists() {
        val base = "perks.sections.public_perks"
        if (!plugin.config.contains(base)) {
            plugin.config.set("$base.enabled", true)
            plugin.config.set("$base.display_name", "<gradient:#56ab2f:#a8e063>🌿 Public Perks</gradient>")
            plugin.config.set("$base.icon", "EMERALD")
            plugin.config.createSection("$base.perks")
        } else if (!plugin.config.contains("$base.perks")) {
            plugin.config.createSection("$base.perks")
        }
    }

    private fun publishWinnerCustomPerksToPublic(endedTerm: Int) {
        val winnerUuid = plugin.store.winner(endedTerm) ?: return

        val chosen = plugin.store.chosenPerks(endedTerm, winnerUuid)
        val customIds = chosen.asSequence()
            .filter { it.startsWith("custom:", ignoreCase = true) }
            .mapNotNull { it.substringAfter("custom:").toIntOrNull() }
            .toSet()

        if (customIds.isEmpty()) return

        val requests = plugin.store.listRequests(endedTerm)
            .filter { it.id in customIds && it.status == RequestStatus.APPROVED }

        if (requests.isEmpty()) return

        ensurePublicPerksSectionExists()

        val winnerName = plugin.server.getOfflinePlayer(winnerUuid).name ?: "Unknown"
        val base = "perks.sections.public_perks.perks"

        for (req in requests) {
            // Unique, deterministic key to avoid collisions
            val key = "public_custom_t${endedTerm + 1}_${req.id}"

            val perkBase = "$base.$key"
            if (plugin.config.contains(perkBase)) continue

            plugin.config.set("$perkBase.enabled", true)
            plugin.config.set("$perkBase.display_name", "<aqua>${req.title}</aqua>")
            plugin.config.set(
                "$perkBase.lore",
                listOf(
                    "<gray>Community perk (winner custom).</gray>",
                    "<gray>Originally proposed by:</gray> <white>$winnerName</white>",
                    "<dark_gray>${req.description}</dark_gray>",
                    "<gray>From term #${endedTerm + 1}</gray>"
                )
            )
            plugin.config.set("$perkBase.on_start", req.onStart)
            plugin.config.set("$perkBase.on_end", req.onEnd)

            // Optional metadata (harmless, but nice for debugging)
            plugin.config.set("$perkBase.source_term", endedTerm)
            plugin.config.set("$perkBase.source_request_id", req.id)
            plugin.config.set("$perkBase.source_candidate", winnerUuid.toString())
        }
    }
}
