package mayorSystem.service

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.config.SystemGateOption
import mayorSystem.data.RequestStatus
import org.bukkit.entity.Player
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AdminActions(private val plugin: MayorPlugin) {

    private fun actorName(actor: Player?): String = actor?.name ?: "CONSOLE"
    private fun actorUuid(actor: Player?): String? = actor?.uniqueId?.toString()

    private fun log(
        actor: Player?,
        action: String,
        term: Int? = null,
        target: String? = null,
        details: Map<String, String> = emptyMap()
    ) {
        plugin.audit.log(
            actorUuid = actorUuid(actor),
            actorName = actorName(actor),
            action = action,
            term = term,
            target = target,
            details = details
        )
    }

    fun updateConfig(path: String, value: Any?, reload: Boolean = true) =
        updateConfigInternal(null, path, value, reload)

    /**
     * Update config with an optional actor.
     *
     * Note: We intentionally accept a nullable [Player] to support console-triggered actions
     * and older call sites. Kotlin nullability does not exist in JVM method signatures, so we
     * must avoid having both `Player` and `Player?` overloads with the same name.
     */
    fun updateConfig(actor: Player?, path: String, value: Any?, reload: Boolean = true) =
        updateConfigInternal(actor, path, value, reload)

    fun updatePerkConfig(path: String, value: Any?) = updatePerkConfig(null, path, value)

    fun updatePerkConfig(actor: Player?, path: String, value: Any?) {
        updateConfigInternal(actor, path, value, reload = false)
        reloadPerksOnly()
    }

    fun updateSettingsConfig(path: String, value: Any?) = updateSettingsConfig(null, path, value)

    fun updateSettingsConfig(actor: Player?, path: String, value: Any?) {
        val wasEnabled = if (path == "enabled") plugin.settings.enabled else null
        val wasPaused = if (path == "pause.enabled") plugin.settings.pauseEnabled else null
        updateConfigInternal(actor, path, value, reload = false)
        reloadSettingsOnly()
        if (path == "enabled" && wasEnabled != null) {
            handleEnabledToggle(actor, wasEnabled, plugin.settings.enabled)
        }
        if (path == "pause.enabled" && wasPaused != null) {
            handlePauseToggle(actor, wasPaused, plugin.settings.pauseEnabled)
        }
    }

    // Internal implementation.
    private fun updateConfigInternal(actor: Player?, path: String, value: Any?, reload: Boolean = true) {
        val prev = plugin.config.get(path)?.toString()
        plugin.config.set(path, value)
        plugin.saveConfig()
        log(actor, "CONFIG_SET", details = mapOf(
            "path" to path,
            "from" to (prev ?: "<null>"),
            "to" to (value?.toString() ?: "<null>"),
            "reload" to reload.toString()
        ))
        if (reload) plugin.reloadEverything()
    }

    private fun reloadPerksOnly() {
        plugin.perks.reloadFromConfig()
        if (plugin.hasTermService()) {
            val term = if (plugin.settings.isBlocked(mayorSystem.config.SystemGateOption.PERKS)) -1 else plugin.termService.computeNow().first
            plugin.perks.rebuildActiveEffectsForTerm(term)
        }
    }

    private fun reloadSettingsOnly() {
        plugin.reloadSettingsOnly()
    }

    private fun handleEnabledToggle(actor: Player?, wasEnabled: Boolean, nowEnabled: Boolean) {
        if (wasEnabled == nowEnabled) return
        val affectsPerks = plugin.settings.enableOptions.contains(mayorSystem.config.SystemGateOption.PERKS)
        val affectsNpc = plugin.settings.enableOptions.contains(mayorSystem.config.SystemGateOption.MAYOR_NPC)
        val affectsSchedule = plugin.settings.enableOptions.contains(mayorSystem.config.SystemGateOption.SCHEDULE)
        if (!nowEnabled) {
            if (affectsPerks) {
                clearActivePerks()
            }
            if (affectsNpc && plugin.hasMayorNpc()) {
                plugin.mayorNpc.forceUpdateMayorForTerm(-1)
            }
            log(actor, "SYSTEM_DISABLED")
            return
        }

        if (plugin.hasTermService() && affectsSchedule) {
            val term = plugin.termService.computeNow().first
            if (affectsPerks) {
                plugin.perks.rebuildActiveEffectsForTerm(term)
            }
            plugin.termService.tick()
        } else if (plugin.hasTermService() && affectsPerks) {
            val term = plugin.termService.computeNow().first
            plugin.perks.rebuildActiveEffectsForTerm(term)
        }
        if (affectsNpc && plugin.hasMayorNpc()) {
            plugin.mayorNpc.forceUpdateMayor()
        }
        log(actor, "SYSTEM_ENABLED")
    }

    private fun clearActivePerks() {
        val term = if (plugin.hasTermService()) plugin.termService.computeNow().first else -1
        if (term >= 0) {
            runCatching { plugin.perks.clearPerks(term) }
        }
        plugin.perks.rebuildActiveEffectsForTerm(-1)
    }

    private fun handlePauseToggle(actor: Player?, wasPaused: Boolean, nowPaused: Boolean) {
        if (wasPaused == nowPaused) return
        val now = OffsetDateTime.now().toInstant()
        val affectsSchedule = plugin.settings.pauseOptions.contains(mayorSystem.config.SystemGateOption.SCHEDULE)
        val affectsPerks = plugin.settings.pauseOptions.contains(mayorSystem.config.SystemGateOption.PERKS)
        val affectsNpc = plugin.settings.pauseOptions.contains(mayorSystem.config.SystemGateOption.MAYOR_NPC)
        if (nowPaused) {
            if (affectsSchedule) {
                startPauseIfNeeded(now)
            }
            if (affectsPerks) {
                clearActivePerks()
            }
            if (affectsNpc && plugin.hasMayorNpc()) {
                plugin.mayorNpc.forceUpdateMayorForTerm(-1)
            }
            log(actor, "SYSTEM_PAUSED")
            return
        }

        if (affectsSchedule) {
            endPauseIfNeeded(now)
            if (plugin.hasTermService()) {
                plugin.termService.invalidateScheduleCache()
            }
        }
        if (affectsPerks && plugin.hasTermService()) {
            val term = if (plugin.settings.isBlocked(mayorSystem.config.SystemGateOption.PERKS)) -1 else plugin.termService.computeNow().first
            plugin.perks.rebuildActiveEffectsForTerm(term)
        }
        if (affectsNpc && plugin.hasMayorNpc()) {
            plugin.mayorNpc.forceUpdateMayor()
        }
        log(actor, "SYSTEM_RESUMED")
    }

    private fun startPauseIfNeeded(now: java.time.Instant) {
        val startedRaw = plugin.config.getString("admin.pause.started_at")
        if (!startedRaw.isNullOrBlank()) return
        plugin.config.set("admin.pause.started_at", now.toString())
        plugin.saveConfig()
    }

    private fun endPauseIfNeeded(now: java.time.Instant) {
        val startedRaw = plugin.config.getString("admin.pause.started_at") ?: return
        val startedAt = runCatching { java.time.Instant.parse(startedRaw) }.getOrNull() ?: return
        val deltaMs = Duration.between(startedAt, now).toMillis().coerceAtLeast(0)
        val totalMs = plugin.config.getLong("admin.pause.total_ms", 0L).coerceAtLeast(0)
        plugin.config.set("admin.pause.total_ms", totalMs + deltaMs)
        plugin.config.set("admin.pause.started_at", null)
        plugin.saveConfig()
    }

    suspend fun forceStartElectionNow(): Boolean = forceStartElectionNow(null)

    suspend fun forceStartElectionNow(actor: Player?): Boolean {
        val ok = plugin.termService.forceStartElectionNow()
        if (ok) {
            plugin.termService.invalidateScheduleCache()
        }
        log(actor, "ELECTION_FORCE_START", details = mapOf("ok" to ok.toString()))
        return ok
    }

    suspend fun forceEndElectionNow(): Boolean = forceEndElectionNow(null)

    suspend fun forceEndElectionNow(actor: Player?): Boolean {
        val ok = plugin.termService.forceEndElectionNow()
        if (ok) {
            plugin.termService.invalidateScheduleCache()
        }
        log(actor, "ELECTION_FORCE_END", details = mapOf("ok" to ok.toString()))
        return ok
    }

    suspend fun clearAllOverridesForTerm(term: Int) = clearAllOverridesForTerm(null, term)

    suspend fun clearAllOverridesForTerm(actor: Player?, term: Int) {
        plugin.termService.clearAllOverridesForTerm(term)
        log(actor, "ELECTION_OVERRIDES_CLEAR", term = term)
    }

    fun setForcedMayor(term: Int, uuid: UUID, name: String) = setForcedMayor(null, term, uuid, name)

    fun setForcedMayor(actor: Player?, term: Int, uuid: UUID, name: String) {
        plugin.config.set("admin.forced_mayor.$term.uuid", uuid.toString())
        plugin.config.set("admin.forced_mayor.$term.name", name)
        plugin.saveConfig()
        log(actor, "ELECTION_FORCED_MAYOR_SET", term = term, target = uuid.toString(), details = mapOf("name" to name))
    }

    suspend fun setForcedMayorWithPerks(
        actor: Player?,
        term: Int,
        uuid: UUID,
        name: String,
        perks: Set<String>
    ): Boolean {
        withContext(Dispatchers.IO) {
            plugin.store.setCandidate(term, uuid, name)
            plugin.store.setChosenPerks(term, uuid, perks)
            plugin.store.setPerksLocked(term, uuid, true)
        }
        plugin.config.set("admin.forced_mayor.$term.uuid", uuid.toString())
        plugin.config.set("admin.forced_mayor.$term.name", name)
        plugin.saveConfig()
        log(
            actor,
            "ELECTION_FORCED_MAYOR_SET",
            term = term,
            target = uuid.toString(),
            details = mapOf("name" to name, "perks" to perks.joinToString(","))
        )
        return true
    }

    fun clearForcedMayor(term: Int) = clearForcedMayor(null, term)

    fun clearForcedMayor(actor: Player?, term: Int) {
        plugin.config.set("admin.forced_mayor.$term", null)
        plugin.saveConfig()
        log(actor, "ELECTION_FORCED_MAYOR_CLEAR", term = term)
    }

    suspend fun resetElectionTerms(actor: Player?) {
        val now = OffsetDateTime.now()
        val voteWindow = plugin.settings.voteWindow
        val offset = if (voteWindow.isZero || voteWindow.isNegative) Duration.ofSeconds(1) else voteWindow
        val newStart = now.plus(offset).withNano(0)

        val currentTerm = if (plugin.hasTermService()) plugin.termService.computeNow().first else -1
        if (currentTerm >= 0) {
            runCatching { plugin.perks.clearPerks(currentTerm) }
        }

        withContext(Dispatchers.IO) {
            plugin.store.resetTermData()
        }

        plugin.config.set("admin.election_override", null)
        plugin.config.set("admin.forced_mayor", null)
        plugin.config.set("admin.term_start_override", null)
        plugin.config.set("admin.mayor_vacant", null)
        plugin.config.set("admin.pause.total_ms", 0L)
        plugin.config.set("admin.pause.started_at", null)
        plugin.config.set("pause.enabled", false)
        plugin.config.set("term.first_term_start", newStart.toString())
        plugin.saveConfig()

        plugin.reloadSettingsOnly()
        plugin.perks.rebuildActiveEffectsForTerm(-1)
        if (plugin.hasMayorNpc()) {
            plugin.mayorNpc.forceUpdateMayorForTerm(-1)
        }

        log(actor, "ELECTION_RESET", details = mapOf("first_term_start" to newStart.toString()))
    }

    suspend fun setCandidateStatus(term: Int, uuid: UUID, status: CandidateStatus) =
        setCandidateStatus(null, term, uuid, status)

    suspend fun setCandidateStatus(actor: Player?, term: Int, uuid: UUID, status: CandidateStatus) {
        withContext(Dispatchers.IO) {
            plugin.store.setCandidateStatus(term, uuid, status)
        }
        log(actor, "CANDIDATE_STATUS", term = term, target = uuid.toString(), details = mapOf("status" to status.name))
        if (plugin.hasLeaderboardHologram()) {
            withContext(plugin.mainDispatcher) {
                plugin.leaderboardHologram.refreshIfActive()
            }
        }
    }

    fun findCandidateByName(term: Int, name: String): CandidateEntry? {
        return plugin.store.candidates(term, includeRemoved = true)
            .firstOrNull { it.lastKnownName.equals(name, ignoreCase = true) }
    }

    suspend fun setRequestStatus(term: Int, id: Int, status: RequestStatus) =
        setRequestStatus(null, term, id, status)

    suspend fun setRequestStatus(actor: Player?, term: Int, id: Int, status: RequestStatus) {
        withContext(Dispatchers.IO) {
            plugin.store.setRequestStatus(term, id, status)
        }
        log(actor, "REQUEST_STATUS", term = term, target = id.toString(), details = mapOf("status" to status.name))
    }

    fun togglePerkSection(sectionId: String): Boolean {
        val path = "perks.sections.$sectionId.enabled"
        val enabled = plugin.config.getBoolean(path, true)
        updatePerkConfig(path, !enabled)
        return !enabled
    }

    fun setPerkSectionEnabled(sectionId: String, enabled: Boolean) {
        updatePerkConfig("perks.sections.$sectionId.enabled", enabled)
    }

    fun setPerkSectionEnabled(actor: Player?, sectionId: String, enabled: Boolean) {
        updatePerkConfig(actor, "perks.sections.$sectionId.enabled", enabled)
    }

    fun togglePerk(sectionId: String, perkId: String): Boolean {
        val path = "perks.sections.$sectionId.perks.$perkId.enabled"
        val enabled = plugin.config.getBoolean(path, true)
        updatePerkConfig(path, !enabled)
        return !enabled
    }

    fun setPerkEnabled(sectionId: String, perkId: String, enabled: Boolean) {
        updatePerkConfig("perks.sections.$sectionId.perks.$perkId.enabled", enabled)
    }

    fun setPerkEnabled(actor: Player?, sectionId: String, perkId: String, enabled: Boolean) {
        updatePerkConfig(actor, "perks.sections.$sectionId.perks.$perkId.enabled", enabled)
    }

    suspend fun setApplyBanPermanent(uuid: UUID, name: String) = setApplyBanPermanent(null, uuid, name)

    suspend fun setApplyBanPermanent(actor: Player?, uuid: UUID, name: String) {
        withContext(Dispatchers.IO) {
            plugin.store.setApplyBanPermanent(uuid, name)
        }
        log(actor, "APPLY_BAN_PERM", target = uuid.toString(), details = mapOf("name" to name))
    }

    suspend fun setApplyBanTemp(uuid: UUID, name: String, until: OffsetDateTime) = setApplyBanTemp(null, uuid, name, until)

    suspend fun setApplyBanTemp(actor: Player?, uuid: UUID, name: String, until: OffsetDateTime) {
        withContext(Dispatchers.IO) {
            plugin.store.setApplyBanTemp(uuid, name, until)
        }
        log(actor, "APPLY_BAN_TEMP", target = uuid.toString(), details = mapOf("name" to name, "until" to until.toString()))
    }

    suspend fun clearApplyBan(uuid: UUID) = clearApplyBan(null, uuid)

    suspend fun clearApplyBan(actor: Player?, uuid: UUID) {
        withContext(Dispatchers.IO) {
            plugin.store.clearApplyBan(uuid)
        }
        log(actor, "APPLY_BAN_CLEAR", target = uuid.toString())
    }

    suspend fun forceElectNowWithPerks(term: Int, uuid: UUID, name: String, perks: Set<String>): Boolean =
        forceElectNowWithPerks(null, term, uuid, name, perks)

    suspend fun forceElectNowWithPerks(actor: Player?, term: Int, uuid: UUID, name: String, perks: Set<String>): Boolean {
        if (plugin.settings.isBlocked(SystemGateOption.SCHEDULE)) {
            log(actor, "ELECTION_FORCE_ELECT_NOW", term = term, target = uuid.toString(), details = mapOf("name" to name, "perks" to perks.joinToString(","), "ok" to "false"))
            return false
        }
        withContext(Dispatchers.IO) {
            plugin.store.setCandidate(term, uuid, name)
            plugin.store.setChosenPerks(term, uuid, perks)
            plugin.store.setPerksLocked(term, uuid, true)
        }
        val ok = plugin.termService.forceElectNow(uuid, name)
        log(actor, "ELECTION_FORCE_ELECT_NOW", term = term, target = uuid.toString(), details = mapOf("name" to name, "perks" to perks.joinToString(","), "ok" to ok.toString()))
        return ok
    }

    fun reload(actor: Player?) {
        log(actor, "RELOAD")
        plugin.reloadEverything()
    }

    // ------------------------------------------------------------------
    // Perk refresh
    // ------------------------------------------------------------------

    /**
     * Re-applies the currently active MayorSystem perk potion effects.
     * Returns number of online players refreshed.
     */
    fun refreshPerksAll(actor: Player?): Int {
        val count = plugin.perks.refreshAllOnlinePlayers()
        log(actor, "PERKS_REFRESH_ALL", details = mapOf("players" to count.toString()))
        return count
    }

    /** Re-applies currently active perk potion effects for a single online player. */
    fun refreshPerksPlayer(actor: Player?, target: Player) {
        plugin.perks.refreshPlayer(target)
        log(actor, "PERKS_REFRESH_PLAYER", target = target.uniqueId.toString(), details = mapOf("name" to target.name))
    }
}

