package mayorSystem.service

import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mayorSystem.MayorPlugin
import mayorSystem.config.SystemGateOption
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.RequestStatus
import mayorSystem.security.Perms
import org.bukkit.entity.Player

class AdminActions(private val plugin: MayorPlugin) {

    private companion object {
        private const val KEY_SETTINGS = "settings"
        private const val KEY_RELOAD = "reload"
        private const val KEY_PERK_CATALOG = "perk-catalog"
        private const val KEY_PERK_REFRESH = "perk-refresh"
        private const val KEY_ELECTION = "election"
    }

    private fun actorName(actor: Player?): String = actor?.name ?: "CONSOLE"
    private fun actorUuid(actor: Player?): String? = actor?.uniqueId?.toString()

    private fun hasAnyPerm(actor: Player?, perms: Collection<String>): Boolean {
        if (actor == null) return true
        return perms.any { actor.hasPermission(it) }
    }

    private fun requirePerms(actor: Player?, vararg perms: String): ActionResult? {
        return if (hasAnyPerm(actor, perms.toList())) {
            null
        } else {
            ActionResult.Rejected("errors.no_permission")
        }
    }

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

    private suspend fun <T> serialized(key: String, block: suspend () -> T): T =
        plugin.actionCoordinator.serialized(key, block)

    private suspend fun serializedResult(key: String, block: suspend () -> ActionResult): ActionResult =
        plugin.actionCoordinator.trySerialized(key, block)
            ?: ActionResult.Rejected("errors.action_in_progress")

    fun updateConfig(
        actor: Player?,
        path: String,
        value: Any?,
        reload: Boolean = true,
        successKey: String,
        successPlaceholders: Map<String, String> = emptyMap()
    ): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_SETTINGS_EDIT)?.let { return@runBlocking it }
        serializedResult(if (reload) KEY_RELOAD else KEY_SETTINGS) {
            runCatching {
                updateConfigInternal(actor, path, value, reload)
                ActionResult.Success(successKey, successPlaceholders)
            }.getOrElse {
                plugin.logger.severe("Failed to update config '$path': ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun updatePerkConfig(
        actor: Player?,
        path: String,
        value: Any?,
        successKey: String,
        successPlaceholders: Map<String, String> = emptyMap()
    ): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_PERKS_CATALOG)?.let { return@runBlocking it }
        serializedResult(KEY_PERK_CATALOG) {
            runCatching {
                updateConfigInternal(actor, path, value, reload = false)
                reloadPerksOnly()
                ActionResult.Success(successKey, successPlaceholders)
            }.getOrElse {
                plugin.logger.severe("Failed to update perk config '$path': ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun updateSettingsConfig(
        actor: Player?,
        path: String,
        value: Any?,
        successKey: String,
        successPlaceholders: Map<String, String> = emptyMap()
    ): ActionResult = runBlocking {
        val denied = if (path == "public_enabled") {
            requirePerms(actor, Perms.ADMIN_SETTINGS_EDIT, Perms.ADMIN_SYSTEM_TOGGLE)
        } else {
            requirePerms(actor, Perms.ADMIN_SETTINGS_EDIT)
        }
        denied?.let { return@runBlocking it }
        serializedResult(KEY_SETTINGS) {
            runCatching {
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
                ActionResult.Success(successKey, successPlaceholders)
            }.getOrElse {
                plugin.logger.severe("Failed to update settings '$path': ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun updateConfig(actor: Player?, path: String, value: Any?, reload: Boolean = true): ActionResult =
        updateConfig(actor, path, value, reload, "errors.action_failed")

    fun updatePerkConfig(actor: Player?, path: String, value: Any?): ActionResult =
        updatePerkConfig(actor, path, value, "errors.action_failed")

    fun updateSettingsConfig(actor: Player?, path: String, value: Any?): ActionResult =
        updateSettingsConfig(actor, path, value, "errors.action_failed")

    fun updateSettingsConfig(path: String, value: Any?): ActionResult =
        updateSettingsConfig(null, path, value, "errors.action_failed")

    private fun updateConfigInternal(actor: Player?, path: String, value: Any?, reload: Boolean = true) {
        val prev = plugin.config.get(path)?.toString()
        plugin.config.set(path, value)
        plugin.saveConfig()
        log(
            actor,
            "CONFIG_SET",
            details = mapOf(
                "path" to path,
                "from" to (prev ?: "<null>"),
                "to" to (value?.toString() ?: "<null>"),
                "reload" to reload.toString()
            )
        )
        if (reload) plugin.reloadEverything()
    }

    private fun reloadPerksOnly() {
        plugin.perks.reloadFromConfig()
        if (plugin.hasTermService()) {
            val term = if (plugin.settings.isBlocked(SystemGateOption.PERKS)) -1 else plugin.termService.computeNow().first
            plugin.perks.rebuildActiveEffectsForTerm(term)
        }
    }

    private fun reloadSettingsOnly() {
        plugin.reloadSettingsOnly()
    }

    private fun handleEnabledToggle(actor: Player?, wasEnabled: Boolean, nowEnabled: Boolean) {
        if (wasEnabled == nowEnabled) return
        val affectsPerks = plugin.settings.enableOptions.contains(SystemGateOption.PERKS)
        val affectsNpc = plugin.settings.enableOptions.contains(SystemGateOption.MAYOR_NPC)
        val affectsSchedule = plugin.settings.enableOptions.contains(SystemGateOption.SCHEDULE)
        if (!nowEnabled) {
            if (affectsPerks) clearActivePerks()
            if (affectsNpc && plugin.hasMayorNpc()) plugin.mayorNpc.forceUpdateMayorForTerm(-1)
            log(actor, "SYSTEM_DISABLED")
            return
        }

        if (plugin.hasTermService() && affectsSchedule) {
            val term = plugin.termService.computeNow().first
            if (affectsPerks) plugin.perks.rebuildActiveEffectsForTerm(term)
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
        val affectsSchedule = plugin.settings.pauseOptions.contains(SystemGateOption.SCHEDULE)
        val affectsPerks = plugin.settings.pauseOptions.contains(SystemGateOption.PERKS)
        val affectsNpc = plugin.settings.pauseOptions.contains(SystemGateOption.MAYOR_NPC)
        if (nowPaused) {
            if (affectsSchedule) startPauseIfNeeded(now)
            if (affectsPerks) clearActivePerks()
            if (affectsNpc && plugin.hasMayorNpc()) plugin.mayorNpc.forceUpdateMayorForTerm(-1)
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
            val term = if (plugin.settings.isBlocked(SystemGateOption.PERKS)) -1 else plugin.termService.computeNow().first
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

    fun forceStartElectionNow(actor: Player?): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_ELECTION_START)?.let { return@runBlocking it }
        serializedResult(KEY_ELECTION) {
            runCatching {
                val ok = plugin.termService.forceStartElectionNow()
                if (ok) {
                    plugin.termService.invalidateScheduleCache()
                    log(actor, "ELECTION_FORCE_START", details = mapOf("ok" to "true"))
                    ActionResult.Success("admin.election.started")
                } else {
                    log(actor, "ELECTION_FORCE_START", details = mapOf("ok" to "false"))
                    ActionResult.Failure("admin.election.start_failed")
                }
            }.getOrElse {
                plugin.logger.severe("Failed to force-start election: ${it.message}")
                ActionResult.Failure("admin.election.start_failed")
            }
        }
    }

    fun forceEndElectionNow(actor: Player?): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_ELECTION_END)?.let { return@runBlocking it }
        serializedResult(KEY_ELECTION) {
            runCatching {
                val ok = plugin.termService.forceEndElectionNow()
                if (ok) {
                    plugin.termService.invalidateScheduleCache()
                    log(actor, "ELECTION_FORCE_END", details = mapOf("ok" to "true"))
                    ActionResult.Success("admin.election.ended")
                } else {
                    log(actor, "ELECTION_FORCE_END", details = mapOf("ok" to "false"))
                    ActionResult.Failure("admin.election.end_failed")
                }
            }.getOrElse {
                plugin.logger.severe("Failed to force-end election: ${it.message}")
                ActionResult.Failure("admin.election.end_failed")
            }
        }
    }

    fun clearAllOverridesForTerm(actor: Player?, term: Int): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_ELECTION_CLEAR)?.let { return@runBlocking it }
        serializedResult(KEY_ELECTION) {
            runCatching {
                plugin.termService.clearAllOverridesForTerm(term)
                log(actor, "ELECTION_OVERRIDES_CLEAR", term = term)
                ActionResult.Success(
                    "admin.election.overrides_cleared",
                    mapOf("term" to (term + 1).toString())
                )
            }.getOrElse {
                plugin.logger.severe("Failed to clear election overrides for term $term: ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun findCandidateByName(term: Int, name: String): CandidateEntry? {
        return plugin.store.candidates(term, includeRemoved = true)
            .firstOrNull { it.lastKnownName.equals(name, ignoreCase = true) }
    }

    fun setFakeVoteAdjustment(
        actor: Player?,
        term: Int,
        uuid: UUID,
        name: String,
        amount: Int
    ): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_ELECTION_FAKE_VOTES)?.let { return@runBlocking it }
        serializedResult("$KEY_ELECTION:fakevotes:$term:$uuid") {
            runCatching {
                withContext(Dispatchers.IO) {
                    plugin.store.setFakeVoteAdjustment(term, uuid, amount)
                }
                log(
                    actor,
                    "ELECTION_FAKE_VOTES_SET",
                    term = term,
                    target = uuid.toString(),
                    details = mapOf("name" to name, "fake_votes" to amount.toString())
                )
                if (plugin.hasLeaderboardHologram()) {
                    withContext(plugin.mainDispatcher) {
                        plugin.leaderboardHologram.refreshIfActive()
                    }
                }
                ActionResult.Success(
                    "admin.election.fake_votes_set",
                    mapOf(
                        "term" to (term + 1).toString(),
                        "name" to name,
                        "votes" to amount.toString()
                    )
                )
            }.getOrElse {
                plugin.logger.severe("Failed to set fake votes for $uuid in term $term: ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun setForcedMayorWithPerks(
        actor: Player?,
        term: Int,
        uuid: UUID,
        name: String,
        perks: Set<String>
    ): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_ELECTION_ELECT)?.let { return@runBlocking it }
        serializedResult("$KEY_ELECTION:forced:$term") {
            runCatching {
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
                ActionResult.Success(
                    "admin.election.forced_mayor_set",
                    mapOf("term" to (term + 1).toString(), "name" to name)
                )
            }.getOrElse {
                plugin.logger.severe("Failed to set forced mayor for term $term: ${it.message}")
                ActionResult.Failure("admin.election.force_failed")
            }
        }
    }

    fun clearForcedMayor(actor: Player?, term: Int): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_ELECTION_ELECT)?.let { return@runBlocking it }
        serializedResult("$KEY_ELECTION:forced:$term") {
            runCatching {
                plugin.config.set("admin.forced_mayor.$term", null)
                plugin.saveConfig()
                log(actor, "ELECTION_FORCED_MAYOR_CLEAR", term = term)
                ActionResult.Success(
                    "admin.election.forced_mayor_cleared",
                    mapOf("term" to (term + 1).toString())
                )
            }.getOrElse {
                plugin.logger.severe("Failed to clear forced mayor for term $term: ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun resetElectionTerms(actor: Player?): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_MAINTENANCE_DEBUG)?.let { return@runBlocking it }
        serializedResult(KEY_RELOAD) {
            runCatching {
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
                if (plugin.hasMayorNpc()) plugin.mayorNpc.forceUpdateMayorForTerm(-1)
                if (plugin.hasMayorUsernamePrefix()) plugin.mayorUsernamePrefix.syncAllOnline()

                log(actor, "ELECTION_RESET", details = mapOf("first_term_start" to newStart.toString()))
                ActionResult.Success("admin.settings.election_reset")
            }.getOrElse {
                plugin.logger.severe("Failed to reset election terms: ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun setCandidateStatus(
        actor: Player?,
        term: Int,
        uuid: UUID,
        status: CandidateStatus,
        name: String
    ): ActionResult = runBlocking {
        requirePerms(
            actor,
            Perms.ADMIN_CANDIDATES_PROCESS,
            Perms.ADMIN_CANDIDATES_REMOVE,
            Perms.ADMIN_CANDIDATES_RESTORE
        )?.let { return@runBlocking it }
        serializedResult("candidate:$term:$uuid") {
            runCatching {
                withContext(Dispatchers.IO) {
                    plugin.store.setCandidateStatus(term, uuid, status)
                }
                log(actor, "CANDIDATE_STATUS", term = term, target = uuid.toString(), details = mapOf("status" to status.name))
                if (plugin.hasLeaderboardHologram()) {
                    withContext(plugin.mainDispatcher) {
                        plugin.leaderboardHologram.refreshIfActive()
                    }
                }
                ActionResult.Success(
                    "admin.candidate.status_set",
                    mapOf("name" to name, "status" to status.name, "term" to (term + 1).toString())
                )
            }.getOrElse {
                plugin.logger.severe("Failed to set candidate status for $uuid: ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun setCandidateStatus(actor: Player?, term: Int, uuid: UUID, status: CandidateStatus): ActionResult {
        val name = plugin.store.candidateEntry(term, uuid)?.lastKnownName ?: uuid.toString()
        return setCandidateStatus(actor, term, uuid, status, name)
    }

    fun setRequestStatus(actor: Player?, term: Int, id: Int, status: RequestStatus): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_PERKS_REQUESTS)?.let { return@runBlocking it }
        serializedResult("request:$term:$id") {
            runCatching {
                withContext(Dispatchers.IO) {
                    plugin.store.setRequestStatus(term, id, status)
                }
                log(actor, "REQUEST_STATUS", term = term, target = id.toString(), details = mapOf("status" to status.name))
                val key = if (status == RequestStatus.APPROVED) "admin.perks.request_approved" else "admin.perks.request_denied"
                ActionResult.Success(key, mapOf("id" to id.toString()))
            }.getOrElse {
                plugin.logger.severe("Failed to set request status for #$id: ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun setPerkSectionEnabled(actor: Player?, sectionId: String, enabled: Boolean): ActionResult {
        return updatePerkConfig(
            actor,
            "perks.sections.$sectionId.enabled",
            enabled,
            "admin.perks.section_updated",
            mapOf("section" to sectionId, "state" to if (enabled) "ENABLED" else "DISABLED")
        )
    }

    fun setPerkEnabled(actor: Player?, sectionId: String, perkId: String, enabled: Boolean): ActionResult {
        return updatePerkConfig(
            actor,
            "perks.sections.$sectionId.perks.$perkId.enabled",
            enabled,
            "admin.perks.perk_updated",
            mapOf("section" to sectionId, "perk" to perkId, "state" to if (enabled) "ENABLED" else "DISABLED")
        )
    }

    fun setApplyBanPermanent(actor: Player?, uuid: UUID, name: String): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_CANDIDATES_APPLYBAN)?.let { return@runBlocking it }
        serializedResult("applyban:$uuid") {
            runCatching {
                withContext(Dispatchers.IO) {
                    plugin.store.setApplyBanPermanent(uuid, name)
                }
                log(actor, "APPLY_BAN_PERM", target = uuid.toString(), details = mapOf("name" to name))
                ActionResult.Success("admin.applyban.permanent", mapOf("name" to name))
            }.getOrElse {
                plugin.logger.severe("Failed to set permanent apply ban for $uuid: ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun setApplyBanTemp(actor: Player?, uuid: UUID, name: String, until: OffsetDateTime): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_CANDIDATES_APPLYBAN)?.let { return@runBlocking it }
        serializedResult("applyban:$uuid") {
            runCatching {
                withContext(Dispatchers.IO) {
                    plugin.store.setApplyBanTemp(uuid, name, until)
                }
                log(actor, "APPLY_BAN_TEMP", target = uuid.toString(), details = mapOf("name" to name, "until" to until.toString()))
                val days = Duration.between(OffsetDateTime.now(), until).toDays().coerceAtLeast(1)
                ActionResult.Success("admin.applyban.temp", mapOf("name" to name, "days" to days.toString()))
            }.getOrElse {
                plugin.logger.severe("Failed to set temporary apply ban for $uuid: ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun clearApplyBan(actor: Player?, uuid: UUID, name: String): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_CANDIDATES_APPLYBAN)?.let { return@runBlocking it }
        serializedResult("applyban:$uuid") {
            runCatching {
                withContext(Dispatchers.IO) {
                    plugin.store.clearApplyBan(uuid)
                }
                log(actor, "APPLY_BAN_CLEAR", target = uuid.toString())
                ActionResult.Success("admin.applyban.cleared", mapOf("name" to name))
            }.getOrElse {
                plugin.logger.severe("Failed to clear apply ban for $uuid: ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun clearApplyBan(actor: Player?, uuid: UUID): ActionResult =
        clearApplyBan(actor, uuid, plugin.server.getOfflinePlayer(uuid).name ?: uuid.toString())

    fun forceElectNowWithPerks(
        actor: Player?,
        term: Int,
        uuid: UUID,
        name: String,
        perks: Set<String>
    ): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_ELECTION_ELECT)?.let { return@runBlocking it }
        serializedResult("$KEY_ELECTION:force-elect:$term") {
            runCatching {
                if (plugin.settings.isBlocked(SystemGateOption.SCHEDULE)) {
                    log(actor, "ELECTION_FORCE_ELECT_NOW", term = term, target = uuid.toString(), details = mapOf("name" to name, "perks" to perks.joinToString(","), "ok" to "false"))
                    return@runCatching ActionResult.Rejected("admin.election.force_failed")
                }
                val ok = plugin.termService.forceElectNow(uuid, name)
                if (!ok) {
                    log(actor, "ELECTION_FORCE_ELECT_NOW", term = term, target = uuid.toString(), details = mapOf("name" to name, "perks" to perks.joinToString(","), "ok" to "false"))
                    return@runCatching ActionResult.Failure("admin.election.force_failed")
                }
                withContext(Dispatchers.IO) {
                    plugin.store.setCandidate(term, uuid, name)
                    plugin.store.setChosenPerks(term, uuid, perks)
                    plugin.store.setPerksLocked(term, uuid, true)
                }
                log(actor, "ELECTION_FORCE_ELECT_NOW", term = term, target = uuid.toString(), details = mapOf("name" to name, "perks" to perks.joinToString(","), "ok" to "true"))
                ActionResult.Success("admin.election.force_elected_now", mapOf("name" to name))
            }.getOrElse {
                plugin.logger.severe("Failed to force-elect mayor for term $term: ${it.message}")
                ActionResult.Failure("admin.election.force_failed")
            }
        }
    }

    suspend fun reload(actor: Player?): ActionResult {
        requirePerms(actor, Perms.ADMIN_MAINTENANCE_RELOAD, Perms.ADMIN_SETTINGS_RELOAD)?.let { return it }
        return serializedResult(KEY_RELOAD) {
            log(actor, "RELOAD")
            val ok = plugin.reloadEverythingVerified()
            if (ok) {
                ActionResult.Success("admin.settings.reloaded")
            } else {
                ActionResult.Failure()
            }
        }
    }

    fun refreshPerksAll(actor: Player?): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_PERKS_REFRESH)?.let { return@runBlocking it }
        serializedResult(KEY_PERK_REFRESH) {
            runCatching {
                val count = plugin.perks.refreshAllOnlinePlayers()
                log(actor, "PERKS_REFRESH_ALL", details = mapOf("players" to count.toString()))
                ActionResult.Success("admin.perks.refresh_all", mapOf("count" to count.toString()))
            }.getOrElse {
                plugin.logger.severe("Failed to refresh perks for all players: ${it.message}")
                ActionResult.Failure()
            }
        }
    }

    fun refreshPerksPlayer(actor: Player?, target: Player): ActionResult = runBlocking {
        requirePerms(actor, Perms.ADMIN_PERKS_REFRESH)?.let { return@runBlocking it }
        serializedResult("$KEY_PERK_REFRESH:${target.uniqueId}") {
            runCatching {
                plugin.perks.refreshPlayer(target)
                log(actor, "PERKS_REFRESH_PLAYER", target = target.uniqueId.toString(), details = mapOf("name" to target.name))
                ActionResult.Success("admin.perks.refresh_player", mapOf("name" to target.name))
            }.getOrElse {
                plugin.logger.severe("Failed to refresh perks for ${target.name}: ${it.message}")
                ActionResult.Failure()
            }
        }
    }
}
