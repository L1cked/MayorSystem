package mayorSystem.elections

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.RequestStatus
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import mayorSystem.messaging.MayorBroadcasts
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.logging.Level
import mayorSystem.config.MayorStepdownPolicy
import mayorSystem.config.SystemGateOption

private data class TermStartOverrideResolution(
    val start: Instant,
    val adjusted: Boolean,
    val note: String? = null
)

private data class ScheduleRecoveryState(
    val currentTerm: Int,
    val nextElectionTerm: Int,
    val latestWinnerTerm: Int?
)

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
    private fun mmSafe(input: String): String = input.replace("<", "").replace(">", "")

    /**
     * Hard guardrail: any admin action or scheduled tick that mutates
     * election/term state must run "one at a time".
     *
     * Why: admins can spam-click menu buttons, multiple staff can use menus
     * at the same time, and the scheduled tick can fire mid-action.
     * This lock prevents race-y double-finalizes or partially written state.
     */
    private val stateLock = Mutex()
    private val tickRunning = AtomicBoolean(false)

    // ---------------------------------------------------------------------
    // Election-open broadcast (optional UX)
    // ---------------------------------------------------------------------

    private enum class BroadcastMode { DISABLED, CHAT, TITLE, BOTH }


    /**
     * Deserialize a MiniMessage-formatted string into an Adventure Component.
     */
    private fun deserializeMini(raw: String) = MayorBroadcasts.deserialize(raw)

    // Reflection-only support for PlaceholderAPI (optional dependency)
    private val papiSetPlaceholders: Method? = runCatching {
        val cls = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
        cls.getMethod("setPlaceholders", Player::class.java, String::class.java)
    }.getOrNull()

    private fun applyPlaceholders(p: Player, raw: String): String {
        val m = papiSetPlaceholders ?: return raw
        return runCatching { m.invoke(null, p, raw) as? String }.getOrNull() ?: raw
    }

    private fun replaceBuiltins(
        raw: String,
        termHuman: Int,
        mayorName: String? = null,
        extraPlaceholders: Map<String, String> = emptyMap()
    ): String {
        val titledMayor = mayorNameWithPrefix(mayorName)
        var built = raw
            .replace("%term%", termHuman.toString())
            .replace("%mayor_name%", titledMayor)
            .replace("%title_name%", plugin.settings.titleName)
            .replace("%title_name_lower%", plugin.settings.titleNameLower())
            .replace("%title_command%", plugin.settings.titleCommand)
            .replace("%title_player_prefix%", plugin.settings.resolvedTitlePlayerPrefix())
        if (extraPlaceholders.isNotEmpty()) {
            extraPlaceholders.forEach { (key, value) ->
                built = built.replace("%$key%", value)
            }
        }
        return built
    }

    private fun mayorNameWithPrefix(mayorName: String?): String {
        val baseName = mayorName?.takeIf { it.isNotBlank() } ?: "None"
        if (baseName.equals("None", ignoreCase = true)) return baseName
        val prefix = plugin.settings.resolvedTitlePlayerPrefix().trim()
        return if (prefix.isBlank()) baseName else "$prefix $baseName"
    }

    private fun parseBroadcastMode(raw: String?, default: BroadcastMode): BroadcastMode {
        return when (raw?.uppercase()) {
            "DISABLED", "NONE", "OFF" -> BroadcastMode.DISABLED
            "CHAT" -> BroadcastMode.CHAT
            "BOTH" -> BroadcastMode.BOTH
            "TITLE" -> BroadcastMode.TITLE
            else -> default
        }
    }

    private fun broadcastMode(): BroadcastMode {
        val raw = plugin.config.getString("election.broadcast.mode", "TITLE")
        return parseBroadcastMode(raw, BroadcastMode.TITLE)
    }

    private fun broadcastMode(path: String, default: BroadcastMode): BroadcastMode {
        val raw = plugin.config.getString(path, default.name)
        return parseBroadcastMode(raw, default)
    }

    private fun sendByMode(mode: BroadcastMode, sendChat: () -> Unit, sendTitle: () -> Unit) {
        when (mode) {
            BroadcastMode.DISABLED -> {}
            BroadcastMode.CHAT -> sendChat()
            BroadcastMode.TITLE -> sendTitle()
            BroadcastMode.BOTH -> {
                sendChat()
                sendTitle()
            }
        }
    }

    fun broadcastVoteActivity(termIndex: Int, voterName: String, candidateName: String) {
        if (plugin.settings.isBlocked(SystemGateOption.BROADCASTS)) return
        if (termIndex < 0) return
        if (!plugin.config.getBoolean("election.broadcast.enabled", true)) return

        val mode = broadcastMode("election.broadcast.vote.mode", BroadcastMode.CHAT)
        if (mode == BroadcastMode.DISABLED) return

        val termHuman = termIndex + 1
        val placeholders = mapOf(
            "player_name" to voterName,
            "candidate_name" to candidateName
        )

        val chatLines = plugin.config.getStringList("election.broadcast.vote.chat_lines")
        if (chatLines.isEmpty()) return

        val titleRawCfg = plugin.config.getString("election.broadcast.vote.title") ?: ""
        val subRawCfg = plugin.config.getString("election.broadcast.vote.subtitle") ?: ""
        val fadeInMs = plugin.config.getLong("election.broadcast.title.fade_in_ms", 500L)
        val stayMs = plugin.config.getLong("election.broadcast.title.stay_ms", 4000L)
        val fadeOutMs = plugin.config.getLong("election.broadcast.title.fade_out_ms", 800L)

        val sendChat = {
            MayorBroadcasts.broadcastChat(chatLines) { _, raw ->
                replaceBuiltins(raw, termHuman, extraPlaceholders = placeholders)
            }
        }
        val sendTitle = {
            Bukkit.getOnlinePlayers().forEach { p ->
                val titleBuilt = replaceBuiltins(titleRawCfg, termHuman, extraPlaceholders = placeholders)
                val subBuilt = replaceBuiltins(subRawCfg, termHuman, extraPlaceholders = placeholders)
                val title = deserializeMini(applyPlaceholders(p, titleBuilt))
                val sub = deserializeMini(applyPlaceholders(p, subBuilt))
                p.showTitle(
                    Title.title(
                        title,
                        sub,
                        Title.Times.times(
                            Duration.ofMillis(fadeInMs),
                            Duration.ofMillis(stayMs),
                            Duration.ofMillis(fadeOutMs)
                        )
                    )
                )
            }
        }

        sendByMode(mode, sendChat, sendTitle)
    }

    fun broadcastApplyActivity(termIndex: Int, playerName: String) {
        if (plugin.settings.isBlocked(SystemGateOption.BROADCASTS)) return
        if (termIndex < 0) return
        if (!plugin.config.getBoolean("election.broadcast.enabled", true)) return

        val mode = broadcastMode("election.broadcast.apply.mode", BroadcastMode.DISABLED)
        if (mode == BroadcastMode.DISABLED) return

        val termHuman = termIndex + 1
        val placeholders = mapOf("player_name" to playerName)

        val chatLines = plugin.config.getStringList("election.broadcast.apply.chat_lines")
        if (chatLines.isEmpty()) return

        val titleRawCfg = plugin.config.getString("election.broadcast.apply.title") ?: ""
        val subRawCfg = plugin.config.getString("election.broadcast.apply.subtitle") ?: ""
        val fadeInMs = plugin.config.getLong("election.broadcast.title.fade_in_ms", 500L)
        val stayMs = plugin.config.getLong("election.broadcast.title.stay_ms", 4000L)
        val fadeOutMs = plugin.config.getLong("election.broadcast.title.fade_out_ms", 800L)

        val sendChat = {
            MayorBroadcasts.broadcastChat(chatLines) { _, raw ->
                replaceBuiltins(raw, termHuman, extraPlaceholders = placeholders)
            }
        }
        val sendTitle = {
            Bukkit.getOnlinePlayers().forEach { p ->
                val titleBuilt = replaceBuiltins(titleRawCfg, termHuman, extraPlaceholders = placeholders)
                val subBuilt = replaceBuiltins(subRawCfg, termHuman, extraPlaceholders = placeholders)
                val title = deserializeMini(applyPlaceholders(p, titleBuilt))
                val sub = deserializeMini(applyPlaceholders(p, subBuilt))
                p.showTitle(
                    Title.title(
                        title,
                        sub,
                        Title.Times.times(
                            Duration.ofMillis(fadeInMs),
                            Duration.ofMillis(stayMs),
                            Duration.ofMillis(fadeOutMs)
                        )
                    )
                )
            }
        }

        sendByMode(mode, sendChat, sendTitle)
    }

    private suspend fun maybeBroadcastElectionOpen(now: Instant, electionTermIndex: Int) {
        if (plugin.settings.isBlocked(SystemGateOption.BROADCASTS)) return
        if (electionTermIndex < 0) return
        if (!plugin.config.getBoolean("election.broadcast.enabled", true)) return
        if (!isElectionOpen(now, electionTermIndex)) return
        if (plugin.store.electionOpenAnnounced(electionTermIndex)) return

        // Mark first so if something downstream throws, we still avoid spamming every tick.
        withContext(Dispatchers.IO) {
            plugin.store.setElectionOpenAnnounced(electionTermIndex, true)
        }

        val termHuman = electionTermIndex + 1
        val mode = broadcastMode()

        val chatLines = plugin.config.getStringList("election.broadcast.open.chat_lines")
        if (chatLines.isEmpty()) return

        val titleRawCfg = plugin.config.getString("election.broadcast.open.title") ?: ""
        val subRawCfg = plugin.config.getString("election.broadcast.open.subtitle") ?: ""
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
                val title = deserializeMini(applyPlaceholders(p, titleBuilt))
                val sub = deserializeMini(applyPlaceholders(p, subBuilt))
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

        sendByMode(mode, sendChat, sendTitle)
    }

    private suspend fun maybeBroadcastMayorElected(termIndex: Int, mayorUuid: UUID, mayorName: String) {
        if (plugin.settings.isBlocked(SystemGateOption.BROADCASTS)) return
        if (termIndex < 0) return
        if (!plugin.config.getBoolean("election.broadcast.enabled", true)) return
        if (termActivationComplete(termIndex)) return

        val termHuman = termIndex + 1
        val mode = broadcastMode()

        val baseChatLines = plugin.config.getStringList("election.broadcast.elected.chat_lines")
        if (baseChatLines.isEmpty()) return

        val perkLine = buildPerkSummaryLine(termIndex, mayorUuid, baseChatLines)
        val chatLines = if (perkLine == null) baseChatLines else baseChatLines + perkLine

        val titleRawCfg = plugin.config.getString("election.broadcast.elected.title") ?: ""
        val subRawCfg = plugin.config.getString("election.broadcast.elected.subtitle") ?: ""
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
                val title = deserializeMini(applyPlaceholders(p, titleBuilt))
                val sub = deserializeMini(applyPlaceholders(p, subBuilt))
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

        sendByMode(mode, sendChat, sendTitle)
    }

    private fun buildPerkSummaryLine(termIndex: Int, mayorUuid: UUID, chatLines: List<String>): String? {
        val chosen = plugin.store.chosenPerks(termIndex, mayorUuid)
        if (chosen.isEmpty()) return null

        val displayNames = chosen.map { plugin.perks.displayNameFor(termIndex, it, null) }
            .map { plain.serialize(MayorBroadcasts.deserialize(it)).trim() }
            .filter { it.isNotBlank() }

        if (displayNames.isEmpty()) return null

        val mayorColor = "<yellow>"
        return "<gray>Perks:</gray> $mayorColor${displayNames.joinToString(", ")}"
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
        val (current, electionTerm) = computeTimeline(now)
        val persistedWinnerTerm = latestPersistedWinnerTerm()
        if (persistedWinnerTerm != null && persistedWinnerTerm > current) {
            return persistedWinnerTerm to (persistedWinnerTerm + 1)
        }

        return current to electionTerm
    }

    private fun computeTimeline(now: Instant): Pair<Int, Int> {
        val effectiveNow = effectiveNow(now)
        val anchor0 = termStart(0)

        // before term #1 begins
        if (effectiveNow.isBefore(anchor0)) return -1 to 0

        val termLen = cycleLength()

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
        val derivedCurrent = segmentTermIndex + steps
        val nextAnchorTermIndex = anchors.getOrNull(activeAnchorIndex + 1)?.first
        val current = if (nextAnchorTermIndex == null) {
            derivedCurrent
        } else {
            derivedCurrent.coerceAtMost(nextAnchorTermIndex - 1)
        }

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
        val pauseEnabled = plugin.settings.pauseEnabled &&
            plugin.settings.pauseOptions.contains(SystemGateOption.SCHEDULE)

        // Avoid mutating config off the main thread (e.g., PlaceholderAPI async calls).
        if (!Bukkit.isPrimaryThread()) {
            val pausedMs = if (pauseEnabled && startedAt != null) {
                Duration.between(startedAt, now).toMillis().coerceAtLeast(0)
            } else 0L
            return now.minusMillis(totalMs + pausedMs)
        }

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

    private fun normalizePauseState(now: Instant): Boolean {
        var changed = false
        var totalMs = plugin.config.getLong("admin.pause.total_ms", 0L)
        if (totalMs < 0L) {
            plugin.config.set("admin.pause.total_ms", 0L)
            plugin.logger.warning("Recovered stale pause state: clamped negative admin.pause.total_ms to 0.")
            totalMs = 0L
            changed = true
        }

        val pauseEnabled = plugin.settings.pauseEnabled &&
            plugin.settings.pauseOptions.contains(SystemGateOption.SCHEDULE)
        val startedRaw = plugin.config.getString("admin.pause.started_at")
        val startedAt = startedRaw?.let { runCatching { Instant.parse(it) }.getOrNull() }

        if (!startedRaw.isNullOrBlank() && startedAt == null) {
            plugin.config.set("admin.pause.started_at", null)
            plugin.logger.warning("Recovered stale pause state: cleared invalid admin.pause.started_at='$startedRaw'.")
            changed = true
        }

        if (startedAt != null && startedAt.isAfter(now)) {
            plugin.config.set("admin.pause.started_at", now.toString())
            plugin.logger.warning("Recovered stale pause state: clamped future admin.pause.started_at to now.")
            changed = true
            return true
        }

        if (pauseEnabled && startedAt == null) {
            plugin.config.set("admin.pause.started_at", now.toString())
            plugin.logger.info("Recovered paused schedule state: restored missing admin.pause.started_at.")
            changed = true
        }

        if (!pauseEnabled && startedAt != null) {
            val deltaMs = Duration.between(startedAt, now).toMillis().coerceAtLeast(0)
            plugin.config.set("admin.pause.total_ms", totalMs.coerceAtLeast(0L) + deltaMs)
            plugin.config.set("admin.pause.started_at", null)
            plugin.logger.info("Recovered stale pause state: folded running pause into admin.pause.total_ms and cleared started_at.")
            changed = true
        }

        return changed
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
    suspend fun forceStartElectionNow(): Boolean {
        if (plugin.settings.isBlocked(SystemGateOption.SCHEDULE)) return false

        return stateLock.withLock {
            val now = Instant.now()
            selfHealScheduleState(now, allowActivationRecovery = false)
            val electionTerm = compute(now).second
            if (electionTerm < 0) return@withLock false
            forceStartElectionNowInternal(now, electionTerm)
        }
    }

    suspend fun forceMayorStepdownNow(mayorUuid: UUID, policy: MayorStepdownPolicy): Boolean {
        if (policy == MayorStepdownPolicy.OFF) return false
        if (plugin.settings.isBlocked(SystemGateOption.SCHEDULE)) return false

        return stateLock.withLock {
            val now = Instant.now()
            selfHealScheduleState(now, allowActivationRecovery = false)
            val (currentTerm, electionTerm) = compute(now)
            if (currentTerm < 0) return@withLock false

            val currentMayor = plugin.store.winner(currentTerm)
            if (currentMayor == null || currentMayor != mayorUuid) return@withLock false

            val opened = forceStartElectionNowInternal(now, electionTerm)
            if (!opened) return@withLock false

            if (policy == MayorStepdownPolicy.NO_MAYOR) {
                if (!plugin.settings.isBlocked(SystemGateOption.PERKS)) {
                    try {
                        plugin.perks.clearPerks(currentTerm)
                    } catch (t: Throwable) {
                        plugin.logger.log(Level.SEVERE, "Failed to clear perks for term=$currentTerm", t)
                    }
                }
                withContext(Dispatchers.IO) {
                    plugin.store.clearWinner(currentTerm)
                }
                setMayorVacant(currentTerm, true)
                plugin.saveConfig()
                if (!plugin.settings.isBlocked(SystemGateOption.MAYOR_NPC) && plugin.hasMayorNpc()) {
                    plugin.mayorNpc.forceUpdateMayorForTerm(currentTerm)
                }
                if (plugin.hasMayorUsernamePrefix()) {
                    plugin.mayorUsernamePrefix.syncKnownMayor(null, mayorUuid)
                }
            }

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
    
    suspend fun forceEndElectionNow(): Boolean {
        if (plugin.settings.isBlocked(SystemGateOption.SCHEDULE)) return false

        return stateLock.withLock {
            val now = Instant.now()
            selfHealScheduleState(now, allowActivationRecovery = false)
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
    
    suspend fun forceElectNow(uuid: UUID, name: String): Boolean {
        if (plugin.settings.isBlocked(SystemGateOption.SCHEDULE)) return false

        return stateLock.withLock {
            val now = Instant.now()
            selfHealScheduleState(now, allowActivationRecovery = false)
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
     * - admin.mayor_vacant.<term>
     */
    suspend fun clearAllOverridesForTerm(term: Int) {
        if (term < 0) return
        stateLock.withLock {
            plugin.config.set("admin.election_override.$term", null)
            plugin.config.set("admin.forced_mayor.$term", null)
            plugin.config.set("admin.term_start_override.$term", null)
            plugin.config.set("admin.mayor_vacant.$term", null)
            plugin.saveConfig()
            invalidateScheduleCache()
            selfHealScheduleState(Instant.now(), allowActivationRecovery = false)
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
        if (plugin.settings.isBlocked(SystemGateOption.SCHEDULE)) return
        if (!tickRunning.compareAndSet(false, true)) return
        plugin.scope.launch(plugin.mainDispatcher) {
            try {
                stateLock.withLock {
                    tickInternal()
                }
            } catch (t: Throwable) {
                plugin.logger.log(Level.SEVERE, "Scheduled term tick failed", t)
            } finally {
                tickRunning.set(false)
            }
        }
    }

    suspend fun tickNow() {
        if (plugin.settings.isBlocked(SystemGateOption.SCHEDULE)) return
        stateLock.withLock {
            tickInternal()
        }
    }

    private suspend fun tickInternal() {
        try {
            val now = Instant.now()
            val recovered = selfHealScheduleState(now, allowActivationRecovery = true)
            val currentTerm = recovered.currentTerm
            val nextElectionTerm = recovered.nextElectionTerm

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
                if (needsStart && !isMayorVacant(currentTerm)) {
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
        } finally {
            if (plugin.hasShowcase()) {
                plugin.showcase.sync()
            }
        }
    }

    private suspend fun selfHealScheduleState(now: Instant, allowActivationRecovery: Boolean): ScheduleRecoveryState {
        var configChanged = false

        if (normalizePauseState(now)) {
            configChanged = true
        }

        var provisionalCurrent = computeTimeline(now).first
        val latestWinnerTerm = latestPersistedWinnerTerm()

        if (sanitizeTermStartOverrideConfig(provisionalCurrent, latestWinnerTerm)) {
            configChanged = true
            invalidateScheduleCache()
            provisionalCurrent = computeTimeline(now).first
        }

        var (currentTerm, nextElectionTerm) = computeTimeline(now)

        if (sanitizeElectionOverrideConfig(nextElectionTerm)) {
            configChanged = true
        }
        if (sanitizeForcedMayorConfig(nextElectionTerm)) {
            configChanged = true
        }
        if (sanitizeMayorVacancyConfig(currentTerm)) {
            configChanged = true
        }

        if (configChanged) {
            plugin.saveConfig()
            invalidateScheduleCache()
            val recomputed = computeTimeline(now)
            currentTerm = recomputed.first
            nextElectionTerm = recomputed.second
        }

        if (latestWinnerTerm != null && latestWinnerTerm > currentTerm) {
            reconcileScheduleToLatestWinner(
                now = now,
                currentTerm = currentTerm,
                latestWinnerTerm = latestWinnerTerm
            )
            val recomputed = computeTimeline(now)
            return ScheduleRecoveryState(
                currentTerm = recomputed.first,
                nextElectionTerm = recomputed.second,
                latestWinnerTerm = latestWinnerTerm
            )
        }

        if (allowActivationRecovery && currentTerm >= 0) {
            recoverCurrentTermActivationIfNeeded(currentTerm)
        }

        return ScheduleRecoveryState(
            currentTerm = currentTerm,
            nextElectionTerm = nextElectionTerm,
            latestWinnerTerm = latestWinnerTerm
        )
    }

    private fun sanitizeElectionOverrideConfig(activeElectionTerm: Int): Boolean {
        val section = plugin.config.getConfigurationSection("admin.election_override") ?: return false
        var changed = false
        for (key in section.getKeys(false)) {
            val term = key.toIntOrNull()
            val value = plugin.config.getString("admin.election_override.$key")?.uppercase()
            val shouldRemove = when {
                term == null -> true
                value !in setOf("OPEN", "CLOSED") -> true
                term < activeElectionTerm -> true
                plugin.store.winner(term) != null -> true
                else -> false
            }
            if (!shouldRemove) continue
            plugin.config.set("admin.election_override.$key", null)
            plugin.logger.info("Recovered stale election override: cleared admin.election_override.$key.")
            changed = true
        }
        return changed
    }

    private fun sanitizeForcedMayorConfig(activeElectionTerm: Int): Boolean {
        val section = plugin.config.getConfigurationSection("admin.forced_mayor") ?: return false
        var changed = false
        for (key in section.getKeys(false)) {
            val term = key.toIntOrNull()
            val forced = term?.let { getForcedMayor(it) }
            val shouldRemove = when {
                term == null -> true
                forced == null -> true
                term < activeElectionTerm -> true
                plugin.store.winner(term) != null -> true
                else -> false
            }
            if (!shouldRemove) continue
            plugin.config.set("admin.forced_mayor.$key", null)
            plugin.logger.info("Recovered stale forced mayor override: cleared admin.forced_mayor.$key.")
            changed = true
        }
        return changed
    }

    private fun sanitizeMayorVacancyConfig(currentTerm: Int): Boolean {
        val section = plugin.config.getConfigurationSection("admin.mayor_vacant") ?: return false
        var changed = false
        for (key in section.getKeys(false)) {
            val term = key.toIntOrNull()
            val winner = term?.let { plugin.store.winner(it) }
            val shouldRemove = when {
                term == null -> true
                term != currentTerm -> true
                winner != null -> true
                else -> false
            }
            if (!shouldRemove) continue
            plugin.config.set("admin.mayor_vacant.$key", null)
            plugin.logger.info("Recovered stale vacancy flag: cleared admin.mayor_vacant.$key.")
            changed = true
        }
        return changed
    }

    private fun sanitizeTermStartOverrideConfig(currentTerm: Int, latestWinnerTerm: Int?): Boolean {
        val section = plugin.config.getConfigurationSection("admin.term_start_override") ?: return false
        if (section.getKeys(false).isEmpty()) return false

        val rawEntries = linkedMapOf<String, String?>()
        val validEntries = linkedMapOf<Int, Instant>()
        var changed = false
        for (key in section.getKeys(false)) {
            val rawValue = plugin.config.getString("admin.term_start_override.$key")
            rawEntries[key] = rawValue
            val term = key.toIntOrNull()
            val instant = rawValue?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (term == null || term < 0 || instant == null) {
                plugin.config.set("admin.term_start_override.$key", null)
                plugin.logger.info("Recovered invalid term start override: cleared admin.term_start_override.$key.")
                changed = true
                continue
            }
            validEntries[term] = instant
        }

        val normalized = normalizeTermStartOverrides(validEntries)
        val referenceTerm = maxOf(currentTerm, latestWinnerTerm ?: -1)
        val keepAnchor = if (referenceTerm >= 0) normalized.keys.filter { it <= referenceTerm }.maxOrNull() else null
        val keepTerms = normalized.keys.filterTo(linkedSetOf()) { term ->
            term == keepAnchor || term > referenceTerm
        }

        for ((term, instant) in normalized) {
            val rawValue = rawEntries[term.toString()]
            if (term !in keepTerms) {
                plugin.config.set("admin.term_start_override.$term", null)
                plugin.logger.info("Recovered obsolete term start override: cleared admin.term_start_override.$term.")
                changed = true
                continue
            }
            if (rawValue != instant.toString()) {
                plugin.config.set("admin.term_start_override.$term", instant.toString())
                plugin.logger.info("Recovered normalized term start override: rewrote admin.term_start_override.$term to ${instant}.")
                changed = true
            }
        }

        for (term in validEntries.keys) {
            if (term !in normalized.keys && plugin.config.contains("admin.term_start_override.$term")) {
                plugin.config.set("admin.term_start_override.$term", null)
                plugin.logger.info("Recovered invalid term start override ordering: cleared admin.term_start_override.$term.")
                changed = true
            }
        }

        return changed
    }

    private suspend fun recoverCurrentTermActivationIfNeeded(currentTerm: Int): Boolean {
        val winnerUuid = plugin.store.winner(currentTerm) ?: return false
        if (termActivationComplete(currentTerm) && !isMayorVacant(currentTerm) &&
            getElectionOverride(currentTerm) == null && getForcedMayor(currentTerm) == null
        ) {
            return false
        }

        val winnerName = plugin.store.winnerName(currentTerm)
            ?: Bukkit.getOfflinePlayer(winnerUuid).name
            ?: winnerUuid.toString()
        plugin.logger.warning(
            "Recovered partial term activation for term=$currentTerm: winner exists but activation state was incomplete. " +
                "Re-applying safe term activation steps."
        )

        setMayorVacant(currentTerm, false)
        clearElectionOverride(currentTerm)
        clearForcedMayor(currentTerm)
        plugin.saveConfig()
        invalidateScheduleCache()

        completeTermActivation(
            termIndex = currentTerm,
            winnerUuid = winnerUuid,
            winnerName = winnerName,
            previousMayorUuid = if (currentTerm > 0) plugin.store.winner(currentTerm - 1) else null,
            allowBroadcast = false
        )
        return true
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
    private suspend fun safeFinalizeElectionForTerm(
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

    private suspend fun finalizeElectionForTerm(
        electionTerm: Int,
        currentTermAtCall: Int,
        forcedTermStart: Instant?
    ) {
        val previousTerm = electionTerm - 1
        val previousMayorUuid = if (currentTermAtCall >= 0) plugin.store.winner(currentTermAtCall) else null

        // 1) End the previous term (if any): clear perks + publish winning custom perks + purge requests
        if (previousTerm >= 0 && previousTerm == currentTermAtCall) {
            if (!plugin.settings.isBlocked(SystemGateOption.PERKS)) {
                // Clear perks first so custom onEnd commands still exist before we purge requests.
                // Guardrail: NEVER let a perk crash the election pipeline.
                try {
                    plugin.perks.clearPerks(previousTerm)
                } catch (t: Throwable) {
                    plugin.logger.log(Level.SEVERE, "Failed to clear perks for term=$previousTerm", t)
                }
            }

            // Publish winning custom perk(s) from the term that just ended, then wipe unapproved requests.
            publishWinnerCustomPerksToPublic(previousTerm)
            withContext(Dispatchers.IO) {
                plugin.store.clearUnapprovedRequests(previousTerm)
            }
        }

        // 2) Choose winner for the upcoming term
        val winnerEntry: CandidateEntry? = run {
            val forced = getForcedMayor(electionTerm)
            if (forced != null) {
                // Ensure they're present as a candidate entry so menus/records look sane.
                withContext(Dispatchers.IO) {
                    plugin.store.setCandidate(electionTerm, forced.first, forced.second)
                }
                CandidateEntry(forced.first, forced.second, CandidateStatus.ACTIVE)
            } else {
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
        }

        if (winnerEntry == null) {
            val termHuman = electionTerm + 1
            val mode = broadcastMode()
            val chatLines = plugin.config.getStringList("election.broadcast.no_candidates.chat_lines").ifEmpty {
                plugin.config.getString("election.broadcast.no_candidates.chat")?.let { listOf(it) } ?: emptyList()
            }
            if (chatLines.isEmpty()) return

            val titleRawCfg = plugin.config.getString("election.broadcast.no_candidates.title") ?: ""
            val subRawCfg = plugin.config.getString("election.broadcast.no_candidates.subtitle") ?: ""
            val fadeInMs = plugin.config.getLong("election.broadcast.title.fade_in_ms", 500L)
            val stayMs = plugin.config.getLong("election.broadcast.title.stay_ms", 4000L)
            val fadeOutMs = plugin.config.getLong("election.broadcast.title.fade_out_ms", 800L)

            val sendChat = {
                // Keep chat announcements consistent (header/footer + PAPI if installed).
                MayorBroadcasts.broadcastChat(chatLines) { _, line ->
                    replaceBuiltins(line, termHuman)
                }
            }

            val sendTitle = {
                Bukkit.getOnlinePlayers().forEach { p ->
                    val titleBuilt = replaceBuiltins(titleRawCfg, termHuman)
                    val subBuilt = replaceBuiltins(subRawCfg, termHuman)
                    val title = deserializeMini(applyPlaceholders(p, titleBuilt))
                    val sub = deserializeMini(applyPlaceholders(p, subBuilt))
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

            sendByMode(mode, sendChat, sendTitle)

            val now = Instant.now()
            val requestedStart = now.plus(plugin.settings.voteWindow)
            val resolvedStart = resolveTermStartOverride(electionTerm, requestedStart)
            if (resolvedStart == null) {
                plugin.logger.warning("Rejected reopen election after no candidates: no valid schedule slot remained.")
            } else {
                if (resolvedStart.adjusted) {
                    plugin.logger.info(
                        "Adjusted reopened election schedule for term=$electionTerm " +
                            "from $requestedStart to ${resolvedStart.start}. ${resolvedStart.note.orEmpty()}".trim()
                    )
                }
                setTermStartOverride(electionTerm, resolvedStart.start)
                withContext(Dispatchers.IO) {
                    // Suppress the "election open" broadcast after an extension.
                    plugin.store.setElectionOpenAnnounced(electionTerm, true)
                }
                invalidateScheduleCache()
            }

            clearElectionOverride(electionTerm)
            clearForcedMayor(electionTerm)
            plugin.saveConfig()
            return
        }

        // 3) Mark winner
        withContext(Dispatchers.IO) {
            plugin.store.setWinner(electionTerm, winnerEntry.uuid, winnerEntry.lastKnownName)
        }
        setMayorVacant(electionTerm, false)

        // 4) Start the new term:
        // - If forcedTermStart is provided, we shift the schedule to that instant.
        // - Otherwise we DO NOT touch the schedule (prevents drift on normal ticks).
        if (forcedTermStart != null) {
            val resolvedStart = resolveTermStartOverride(electionTerm, forcedTermStart)
            if (resolvedStart == null) {
                plugin.logger.warning("Rejected forced term start for term=$electionTerm: no valid schedule slot remained.")
            } else {
                if (resolvedStart.adjusted) {
                    plugin.logger.info(
                        "Adjusted forced term start for term=$electionTerm " +
                            "from $forcedTermStart to ${resolvedStart.start}. ${resolvedStart.note.orEmpty()}".trim()
                    )
                }
                setTermStartOverride(electionTerm, resolvedStart.start)
                syncPauseForForcedStart(resolvedStart.start)
                if (electionTerm == 0 && currentTermAtCall < 0) {
                    val offset = plugin.settings.firstTermStart.offset
                    val newStartOdt = OffsetDateTime.ofInstant(resolvedStart.start, offset)
                    plugin.config.set("term.first_term_start", newStartOdt.toString())
                    plugin.reloadSettingsOnly()
                }
            }
        }

        // Public-perks publishing + schedule overrides + admin flags all live in config.yml,
        // so we persist once at the end.
        clearElectionOverride(electionTerm)
        clearForcedMayor(electionTerm)
        plugin.saveConfig()
        invalidateScheduleCache()

        completeTermActivation(
            termIndex = electionTerm,
            winnerUuid = winnerEntry.uuid,
            winnerName = winnerEntry.lastKnownName,
            previousMayorUuid = previousMayorUuid,
            allowBroadcast = true
        )
    }

    private suspend fun completeTermActivation(
        termIndex: Int,
        winnerUuid: UUID,
        winnerName: String,
        previousMayorUuid: UUID?,
        allowBroadcast: Boolean
    ) {
        if (!plugin.settings.isBlocked(SystemGateOption.MAYOR_NPC) && plugin.hasMayorNpc()) {
            plugin.mayorNpc.forceUpdateMayorForTerm(termIndex)
        }

        if (allowBroadcast) {
            maybeBroadcastMayorElected(termIndex, winnerUuid, winnerName)
        }

        if (!plugin.settings.isBlocked(SystemGateOption.PERKS)) {
            try {
                val suppressSay = plugin.config.getBoolean("election.broadcast.enabled", true) &&
                    broadcastMode() != BroadcastMode.TITLE
                plugin.perks.applyPerks(termIndex, suppressSayBroadcast = suppressSay)
            } catch (t: Throwable) {
                plugin.logger.log(Level.SEVERE, "Failed to apply perks for term $termIndex", t)
            }
        }

        if (!plugin.settings.isBlocked(SystemGateOption.MAYOR_NPC) && plugin.hasMayorNpc()) {
            plugin.mayorNpc.forceUpdateMayorForTerm(termIndex)
        }
        if (plugin.hasMayorUsernamePrefix()) {
            plugin.mayorUsernamePrefix.syncKnownMayor(winnerUuid, previousMayorUuid)
        }

        withContext(Dispatchers.IO) {
            plugin.store.setMayorElectedAnnounced(termIndex, true)
        }

        if (plugin.hasShowcase()) {
            plugin.showcase.sync()
        }
    }

    /**
     * If the schedule is paused, pin the effective timeline to the forced start
     * so the new term is visible immediately (even while paused).
     */
    private fun syncPauseForForcedStart(forcedTermStart: Instant) {
        if (!plugin.settings.pauseEnabled || !plugin.settings.pauseOptions.contains(SystemGateOption.SCHEDULE)) return
        val now = Instant.now()
        val offsetMs = Duration.between(forcedTermStart, now).toMillis().coerceAtLeast(0)
        plugin.config.set("admin.pause.total_ms", offsetMs)
        plugin.config.set("admin.pause.started_at", now.toString())
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
        val raw = mutableMapOf<Int, Instant>()
        for (k in sec.getKeys(false)) {
            val idx = k.toIntOrNull() ?: continue
            val rawValue = sec.getString(k) ?: continue
            val inst = runCatching { Instant.parse(rawValue) }.getOrNull() ?: continue
            raw[idx] = inst
        }
        return normalizeTermStartOverrides(raw)
    }

    private fun normalizeTermStartOverrides(raw: Map<Int, Instant>): Map<Int, Instant> {
        if (raw.isEmpty()) return emptyMap()

        val baseStart = plugin.settings.firstTermStart.toInstant()
        val accepted = linkedMapOf<Int, Instant>()
        raw[0]?.let { accepted[0] = it }

        raw.entries
            .filter { it.key > 0 }
            .sortedBy { it.key }
            .forEach { (termIndex, start) ->
                val previousTermStart = termStart(
                    termIndex = termIndex - 1,
                    overrides = accepted,
                    baseStart = accepted[0] ?: baseStart
                )
                if (start.isAfter(previousTermStart)) {
                    accepted[termIndex] = start
                }
            }

        return accepted
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
        return termStart(
            termIndex = termIndex,
            overrides = termStartOverrides(),
            baseStart = plugin.settings.firstTermStart.toInstant()
        )
    }

    private fun termStart(termIndex: Int, overrides: Map<Int, Instant>, baseStart: Instant): Instant {
        if (termIndex <= 0) {
            val override0 = overrides[0]
            return override0 ?: baseStart
        }

        val termLen = cycleLength()

        // Pick the latest override <= termIndex (excluding 0 handled above)
        val best = overrides.keys.filter { it in 1..termIndex }.maxOrNull()
        if (best == null) {
            return baseStart.plus(termLen.multipliedBy(termIndex.toLong()))
        }

        val anchorStart = overrides[best]!!
        val deltaTerms = termIndex - best
        return anchorStart.plus(termLen.multipliedBy(deltaTerms.toLong()))
    }

    private fun cycleLength(): Duration {
        val base = plugin.settings.termLength
        return if (plugin.settings.electionAfterTermEnd) base.plus(plugin.settings.voteWindow) else base
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
        val overrides = termStartOverrides()

        // Don't allow rewriting history (it causes non-obvious state bugs).
        if (termIndex < currentTerm) {
            return "You can only change the current/upcoming term schedule (termIndex >= $currentTerm)."
        }

        if (termIndex > 0) {
            val prevStart = termStart(
                termIndex = termIndex - 1,
                overrides = overrides,
                baseStart = plugin.settings.firstTermStart.toInstant()
            )
            if (!newStart.isAfter(prevStart)) {
                return "Term start must be after the previous term start."
            }
        }

        return null
    }

    private fun resolveTermStartOverride(termIndex: Int, requestedStart: Instant): TermStartOverrideResolution? {
        val validation = validateTermStartOverride(termIndex, requestedStart)
        if (validation != null) return null

        val overrides = termStartOverrides()
        val nextOverride = overrides.entries
            .filter { it.key > termIndex }
            .minByOrNull { it.key }
            ?: return TermStartOverrideResolution(start = requestedStart, adjusted = false)

        val termsUntilNext = nextOverride.key - termIndex - 1
        val latestExclusiveStart = if (termsUntilNext <= 0) {
            nextOverride.value
        } else {
            nextOverride.value.minus(cycleLength().multipliedBy(termsUntilNext.toLong()))
        }

        if (requestedStart.isBefore(latestExclusiveStart)) {
            return TermStartOverrideResolution(start = requestedStart, adjusted = false)
        }

        val clampedStart = latestExclusiveStart.minusMillis(1)
        val previousStart = if (termIndex > 0) {
            termStart(
                termIndex = termIndex - 1,
                overrides = overrides,
                baseStart = plugin.settings.firstTermStart.toInstant()
            )
        } else {
            null
        }

        if (previousStart != null && !clampedStart.isAfter(previousStart)) {
            return null
        }

        return TermStartOverrideResolution(
            start = clampedStart,
            adjusted = true,
            note = "Clamped to stay before term ${nextOverride.key + 1}."
        )
    }

    private fun setTermStartOverride(termIndex: Int, start: Instant) {
        plugin.config.set("admin.term_start_override.$termIndex", start.toString())
    }

    private fun isMayorVacant(termIndex: Int): Boolean =
        plugin.config.getBoolean("admin.mayor_vacant.$termIndex", false)

    private fun setMayorVacant(termIndex: Int, value: Boolean) {
        if (value) {
            plugin.config.set("admin.mayor_vacant.$termIndex", true)
        } else if (plugin.config.contains("admin.mayor_vacant.$termIndex")) {
            plugin.config.set("admin.mayor_vacant.$termIndex", null)
        }
    }

    private fun termActivationComplete(termIndex: Int): Boolean =
        plugin.store.mayorElectedAnnounced(termIndex)

    private fun latestPersistedWinnerTerm(): Int? =
        runCatching { plugin.store.highestWinnerTermOrNull() }.getOrNull()

    private suspend fun reconcileScheduleToLatestWinner(
        now: Instant,
        currentTerm: Int,
        latestWinnerTerm: Int
    ) {
        // Align to the effective schedule timeline, not wall-clock now.
        // Otherwise a retained admin.pause.total_ms keeps the recovered term
        // perpetually "in the future" and the stale-schedule recovery repeats forever.
        val requestedStart = effectiveNow(now).minusMillis(1)
        val resolvedStart = resolveTermStartOverride(latestWinnerTerm, requestedStart)
        val appliedStart = resolvedStart?.start ?: requestedStart
        val rebased = if (resolvedStart != null) {
            setTermStartOverride(latestWinnerTerm, resolvedStart.start)
            false
        } else {
            rebaseScheduleToCurrentTerm(latestWinnerTerm, requestedStart)
            true
        }

        if (plugin.settings.pauseEnabled && plugin.settings.pauseOptions.contains(SystemGateOption.SCHEDULE)) {
            syncPauseForForcedStart(appliedStart)
        }

        if (currentTerm >= 0 && currentTerm != latestWinnerTerm && !plugin.settings.isBlocked(SystemGateOption.PERKS)) {
            try {
                plugin.perks.clearPerks(currentTerm)
            } catch (t: Throwable) {
                plugin.logger.log(Level.SEVERE, "Failed to clear perks while reconciling stale term state", t)
            }
        }

        setMayorVacant(latestWinnerTerm, false)
        clearElectionOverride(latestWinnerTerm)
        clearForcedMayor(latestWinnerTerm)
        plugin.saveConfig()
        invalidateScheduleCache()
        if (rebased) {
            plugin.reloadSettingsOnly()
        }

        val winnerUuid = plugin.store.winner(latestWinnerTerm)
        if (winnerUuid != null) {
            val winnerName = plugin.store.winnerName(latestWinnerTerm)
                ?: Bukkit.getOfflinePlayer(winnerUuid).name
                ?: winnerUuid.toString()
            val previousMayorUuid = if (latestWinnerTerm > 0) plugin.store.winner(latestWinnerTerm - 1) else null
            completeTermActivation(
                termIndex = latestWinnerTerm,
                winnerUuid = winnerUuid,
                winnerName = winnerName,
                previousMayorUuid = previousMayorUuid,
                allowBroadcast = false
            )
        }

        plugin.logger.warning(
            "Detected stale schedule state: computed currentTerm=$currentTerm but winner already exists for term=$latestWinnerTerm. " +
                "Fast-forwarded schedule to the latest persisted winner" +
                if (rebased) " by rebasing the schedule anchor." else "."
        )
    }

    private fun rebaseScheduleToCurrentTerm(termIndex: Int, requestedStart: Instant) {
        val cycle = cycleLength()
        val baseStart = requestedStart.minus(cycle.multipliedBy(termIndex.toLong()))
        val offset = plugin.settings.firstTermStart.offset
        val newStartOdt = OffsetDateTime.ofInstant(baseStart, offset)
        plugin.config.set("admin.term_start_override", null)
        plugin.config.set("term.first_term_start", newStartOdt.toString())
        invalidateScheduleCache()
    }

    private suspend fun forceStartElectionNowInternal(now: Instant, electionTerm: Int): Boolean {
        // Set term start so electionOpen == now (because electionOpen = termStart - voteWindow).
        val wasPreTerm = compute(now).first < 0
        val requestedStart = now.plus(plugin.settings.voteWindow)
        val resolvedStart = resolveTermStartOverride(electionTerm, requestedStart)
        if (resolvedStart == null) {
            plugin.logger.warning("Rejected forceStartElectionNow: no valid schedule slot remained.")
            return false
        }
        if (resolvedStart.adjusted) {
            plugin.logger.info(
                "Adjusted forceStartElectionNow for term=$electionTerm " +
                    "from $requestedStart to ${resolvedStart.start}. ${resolvedStart.note.orEmpty()}".trim()
            )
        }
        setTermStartOverride(electionTerm, resolvedStart.start)

        // Clear hard overrides to avoid confusing UI.
        clearElectionOverride(electionTerm)

        // If we just force-started the very first election, keep the base schedule
        // aligned with the new start so menus/seeded tie-breaks stay consistent.
        val shouldUpdateFirstStart = electionTerm == 0 && wasPreTerm
        if (shouldUpdateFirstStart) {
            val offset = plugin.settings.firstTermStart.offset
            val newStartOdt = OffsetDateTime.ofInstant(resolvedStart.start, offset)
            plugin.config.set("term.first_term_start", newStartOdt.toString())
        }

        plugin.saveConfig()
        invalidateScheduleCache()
        if (shouldUpdateFirstStart) {
            plugin.reloadSettingsOnly()
        }

        // Make sure the live state reflects the change immediately.
        // (no-op most of the time, but makes admin actions feel instant)
        tickInternal()
        return true
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

            val safeTitle = mmSafe(req.title)
            val safeDesc = mmSafe(req.description)

            plugin.config.set("$perkBase.enabled", true)
            plugin.config.set("$perkBase.display_name", "<aqua>$safeTitle</aqua>")
            plugin.config.set(
                "$perkBase.lore",
                listOf(
                    "<gray>Community perk (winner custom).</gray>",
                    "<gray>Originally proposed by:</gray> <white>$winnerName</white>",
                    "<dark_gray>$safeDesc</dark_gray>",
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

        // Remove published custom perks from the player's custom request pool.
        plugin.store.removeRequests(endedTerm, requests.map { it.id }.toSet())
    }
}

