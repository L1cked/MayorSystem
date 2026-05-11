package mayorSystem.application.usecase

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mayorSystem.MayorPlugin
import mayorSystem.config.SystemGateOption
import mayorSystem.data.repository.ElectionRepository
import mayorSystem.data.repository.MayorStoreElectionRepository
import mayorSystem.security.Perms
import mayorSystem.service.ActionResult
import org.bukkit.entity.Player

/**
 * Command-facing admin election actions.
 *
 * This preserves the existing AdminActions behavior while moving election lifecycle
 * workflows out of the broad compatibility facade.
 */
class AdminElectionCommandActions(
    private val plugin: MayorPlugin,
    private val electionRepository: ElectionRepository = MayorStoreElectionRepository(plugin.store)
) {
    private val support = AdminActionSupport(plugin)

    private companion object {
        private const val KEY_ELECTION = "election"
        private const val KEY_RELOAD = "reload"
        private const val FIRST_TERM_START_PATH = "term.first_term_start"
        private const val RESET_FIRST_TERM_START = "9999-12-31T23:59:59-05:00"
        private const val MAX_FAKE_VOTES = 1_000_000
    }

    suspend fun forceStartElectionNow(actor: Player?): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_ELECTION_START)?.let { return it }
        return support.serializedResult(KEY_ELECTION) {
            runCatching {
                val ok = plugin.termService.forceStartElectionNow()
                if (ok) {
                    plugin.termService.invalidateScheduleCache()
                    support.log(actor, "ELECTION_FORCE_START", details = mapOf("ok" to "true"))
                    ActionResult.Success("admin.election.started")
                } else {
                    support.log(actor, "ELECTION_FORCE_START", details = mapOf("ok" to "false"))
                    ActionResult.Failure("admin.election.start_failed")
                }
            }.getOrElse {
                support.logFailure("Failed to force-start election.", it)
                ActionResult.Failure("admin.election.start_failed")
            }
        }
    }

    suspend fun forceEndElectionNow(actor: Player?): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_ELECTION_END)?.let { return it }
        return support.serializedResult(KEY_ELECTION) {
            runCatching {
                val ok = plugin.termService.forceEndElectionNow()
                if (ok) {
                    plugin.termService.invalidateScheduleCache()
                    support.log(actor, "ELECTION_FORCE_END", details = mapOf("ok" to "true"))
                    ActionResult.Success("admin.election.ended")
                } else {
                    support.log(actor, "ELECTION_FORCE_END", details = mapOf("ok" to "false"))
                    ActionResult.Failure("admin.election.end_failed")
                }
            }.getOrElse {
                support.logFailure("Failed to force-end election.", it)
                ActionResult.Failure("admin.election.end_failed")
            }
        }
    }

    suspend fun clearAllOverridesForTerm(
        actor: Player?,
        term: Int,
        requireCurrentElectionTerm: Boolean = false
    ): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_ELECTION_CLEAR)?.let { return it }
        return support.serializedResult(KEY_ELECTION) {
            runCatching {
                val targetTerm = support.onMain {
                    if (plugin.settings.isBlocked(SystemGateOption.SCHEDULE)) return@onMain null
                    val (_, currentElectionTerm) = plugin.termService.computeCached(Instant.now())
                    term.takeIf {
                        it >= 0 && (!requireCurrentElectionTerm || (currentElectionTerm >= 0 && it == currentElectionTerm))
                    }
                } ?: return@runCatching ActionResult.Rejected("admin.election.term_changed")
                val terminalCleared = withContext(plugin.mainDispatcher) {
                    plugin.termService.clearAllOverridesForTerm(targetTerm)
                }
                support.log(
                    actor,
                    "ELECTION_OVERRIDES_CLEAR",
                    term = targetTerm,
                    details = mapOf("terminal_vacant_cleared" to terminalCleared.toString())
                )
                ActionResult.Success(
                    if (terminalCleared) "admin.election.overrides_cleared_terminal" else "admin.election.overrides_cleared",
                    mapOf("term" to (targetTerm + 1).toString())
                )
            }.getOrElse {
                support.logFailure("Failed to clear election overrides for term $term.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun setFakeVoteAdjustment(
        actor: Player?,
        term: Int,
        uuid: UUID,
        name: String,
        amount: Int
    ): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_ELECTION_FAKE_VOTES)?.let { return it }
        return support.serializedResult("$KEY_ELECTION:fakevotes:$term:$uuid") {
            runCatching {
                val rejected = withContext(Dispatchers.IO) {
                    val realVotes = electionRepository.realVoteCounts(term)[uuid] ?: 0
                    if (amount !in -realVotes..MAX_FAKE_VOTES) {
                        return@withContext ActionResult.Rejected("admin.election.fake_votes_invalid")
                    }
                    electionRepository.setFakeVoteAdjustment(term, uuid, amount)
                    null
                }
                rejected?.let { return@runCatching it }
                support.log(
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
                support.logFailure("Failed to set fake votes for $uuid in term $term.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun setForcedMayorWithPerks(
        actor: Player?,
        term: Int,
        uuid: UUID,
        name: String,
        perks: Set<String>
    ): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_ELECTION_ELECT)?.let { return it }
        return support.serializedResult("$KEY_ELECTION:forced:$term") {
            runCatching {
                validateForceElectSelection(actor, term, uuid, name, perks)?.let { return@runCatching it }
                withContext(Dispatchers.IO) {
                    electionRepository.setCandidate(term, uuid, name)
                    electionRepository.setChosenPerks(term, uuid, perks)
                    electionRepository.setPerksLocked(term, uuid, true)
                }
                saveForcedMayorConfig(term, uuid, name)
                support.log(
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
                support.logFailure("Failed to set forced mayor for term $term.", it)
                ActionResult.Failure("admin.election.force_failed")
            }
        }
    }

    suspend fun clearForcedMayor(actor: Player?, term: Int): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_ELECTION_ELECT)?.let { return it }
        return support.serializedResult("$KEY_ELECTION:forced:$term") {
            runCatching {
                clearForcedMayorConfig(term)
                support.log(actor, "ELECTION_FORCED_MAYOR_CLEAR", term = term)
                ActionResult.Success(
                    "admin.election.forced_mayor_cleared",
                    mapOf("term" to (term + 1).toString())
                )
            }.getOrElse {
                support.logFailure("Failed to clear forced mayor for term $term.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun resetElectionTerms(actor: Player?): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_MAINTENANCE_DEBUG)?.let { return it }
        return support.serializedResult(KEY_RELOAD) {
            runCatching {
                val currentTerm = if (plugin.hasTermService()) plugin.termService.computeNow().first else -1
                if (currentTerm >= 0) {
                    runCatching { plugin.perks.clearPerksSuspending(currentTerm) }
                }

                withContext(Dispatchers.IO) {
                    electionRepository.resetTermData()
                }

                resetElectionConfig()

                plugin.logger.info(
                    "Election reset cleared all election admin overrides, terminal vacant state, and moved term.first_term_start to $RESET_FIRST_TERM_START."
                )

                plugin.reloadSettingsOnly()
                plugin.perks.rebuildActiveEffectsForTerm(-1)
                if (plugin.hasMayorNpc()) plugin.mayorNpc.forceUpdateMayorForTerm(-1)
                if (plugin.hasMayorUsernamePrefix()) plugin.mayorUsernamePrefix.syncAllOnline()

                support.log(actor, "ELECTION_RESET", details = mapOf("first_term_start" to RESET_FIRST_TERM_START))
                ActionResult.Success("admin.settings.election_reset")
            }.getOrElse {
                support.logFailure("Failed to reset election terms.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun forceElectNowWithPerks(
        actor: Player?,
        term: Int,
        uuid: UUID,
        name: String,
        perks: Set<String>
    ): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_ELECTION_ELECT)?.let { return it }
        return support.serializedResult("$KEY_ELECTION:force-elect:$term") {
            runCatching {
                validateForceElectSelection(actor, term, uuid, name, perks)?.let { result ->
                    support.log(actor, "ELECTION_FORCE_ELECT_NOW", term = term, target = uuid.toString(), details = mapOf("name" to name, "perks" to perks.joinToString(","), "ok" to "false"))
                    return@runCatching result
                }
                val ok = withContext(plugin.mainDispatcher) {
                    plugin.termService.forceElectNow(uuid, name, perks)
                }
                if (!ok) {
                    support.log(actor, "ELECTION_FORCE_ELECT_NOW", term = term, target = uuid.toString(), details = mapOf("name" to name, "perks" to perks.joinToString(","), "ok" to "false"))
                    return@runCatching ActionResult.Failure("admin.election.force_failed")
                }
                support.log(actor, "ELECTION_FORCE_ELECT_NOW", term = term, target = uuid.toString(), details = mapOf("name" to name, "perks" to perks.joinToString(","), "ok" to "true"))
                ActionResult.Success("admin.election.force_elected_now", mapOf("name" to name))
            }.getOrElse {
                support.logFailure("Failed to force-elect mayor for term $term.", it)
                ActionResult.Failure("admin.election.force_failed")
            }
        }
    }

    private suspend fun validateForceElectSelection(
        actor: Player?,
        term: Int,
        uuid: UUID,
        name: String,
        perks: Set<String>
    ): ActionResult? = withContext(plugin.mainDispatcher) {
        support.requirePerms(actor, Perms.ADMIN_ELECTION_ELECT)?.let { return@withContext it }
        if (plugin.settings.isBlocked(SystemGateOption.SCHEDULE)) {
            return@withContext ActionResult.Rejected("admin.election.force_failed")
        }
        if (term < 0 || name.isBlank()) {
            return@withContext ActionResult.Rejected("admin.election.force_failed")
        }
        val (_, currentElectionTerm) = plugin.termService.computeCached(Instant.now())
        if (currentElectionTerm != term) {
            return@withContext ActionResult.Rejected("admin.election.term_changed")
        }

        val allowed = plugin.settings.perksAllowed(term)
        if (perks.size != allowed) {
            return@withContext ActionResult.Rejected(
                "admin.perks.perk_exact",
                mapOf("limit" to allowed.toString(), "selected" to perks.size.toString())
            )
        }

        val violations = plugin.perks.sectionLimitViolations(perks)
        if (violations.isNotEmpty()) {
            val summary = violations.joinToString(", ") { "${it.first} (${it.second})" }
            return@withContext ActionResult.Rejected(
                "admin.perks.section_limit_violation",
                mapOf("sections" to summary)
            )
        }

        val availableIds = plugin.perks.availablePerksForCandidate(term, uuid).mapTo(linkedSetOf()) { it.id }
        if (!availableIds.containsAll(perks)) {
            return@withContext ActionResult.Rejected("admin.election.force_invalid_perks")
        }

        null
    }

    private suspend fun saveForcedMayorConfig(term: Int, uuid: UUID, name: String) {
        support.onMain {
            plugin.config.set("admin.forced_mayor.$term.uuid", uuid.toString())
            plugin.config.set("admin.forced_mayor.$term.name", name)
            plugin.saveConfig()
        }
    }

    private suspend fun clearForcedMayorConfig(term: Int) {
        support.onMain {
            plugin.config.set("admin.forced_mayor.$term", null)
            plugin.saveConfig()
        }
    }

    private suspend fun resetElectionConfig() {
        support.onMain {
            plugin.config.set("admin.election_override", null)
            plugin.config.set("admin.forced_mayor", null)
            plugin.config.set("admin.term_start_override", null)
            plugin.config.set("admin.mayor_vacant", null)
            plugin.config.set("admin.election_terminal_vacant", null)
            plugin.config.set("admin.pause", null)
            plugin.config.set("pause.enabled", false)
            plugin.config.set(FIRST_TERM_START_PATH, RESET_FIRST_TERM_START)
            plugin.saveConfig()
        }
    }

}
