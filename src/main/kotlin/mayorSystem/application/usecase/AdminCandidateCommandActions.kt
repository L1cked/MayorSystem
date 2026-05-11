package mayorSystem.application.usecase

import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.repository.ElectionRepository
import mayorSystem.data.repository.MayorStoreElectionRepository
import mayorSystem.security.Perms
import mayorSystem.service.ActionResult
import org.bukkit.entity.Player

/**
 * Command-facing admin candidate actions.
 *
 * Keeps the existing storage, permission, audit, and refresh behavior while
 * moving candidate moderation out of the broad AdminActions facade.
 */
class AdminCandidateCommandActions(
    private val plugin: MayorPlugin,
    private val electionRepository: ElectionRepository = MayorStoreElectionRepository(plugin.store)
) {
    private val support = AdminActionSupport(plugin)

    fun findCandidateByName(term: Int, name: String): CandidateEntry? {
        return plugin.store.candidates(term, includeRemoved = true)
            .firstOrNull { it.lastKnownName.equals(name, ignoreCase = true) }
    }

    suspend fun setCandidateStatus(
        actor: Player?,
        term: Int,
        uuid: UUID,
        status: CandidateStatus,
        name: String
    ): ActionResult {
        requireCandidateStatusPerm(actor, term, uuid, status)?.let { return it }
        return support.serializedResult("candidate:$term:$uuid") {
            runCatching {
                withContext(Dispatchers.IO) {
                    electionRepository.setCandidateStatus(term, uuid, status)
                }
                support.log(actor, "CANDIDATE_STATUS", term = term, target = uuid.toString(), details = mapOf("status" to status.name))
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
                support.logFailure("Failed to set candidate status for $uuid.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun setCandidateStatus(actor: Player?, term: Int, uuid: UUID, status: CandidateStatus): ActionResult {
        val name = withContext(Dispatchers.IO) {
            electionRepository.candidateEntry(term, uuid)?.lastKnownName
        } ?: uuid.toString()
        return setCandidateStatus(actor, term, uuid, status, name)
    }

    suspend fun setApplyBanPermanent(actor: Player?, uuid: UUID, name: String): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_CANDIDATES_APPLYBAN)?.let { return it }
        return support.serializedResult("applyban:$uuid") {
            runCatching {
                withContext(Dispatchers.IO) {
                    electionRepository.setApplyBanPermanent(uuid, name)
                }
                support.log(actor, "APPLY_BAN_PERM", target = uuid.toString(), details = mapOf("name" to name))
                ActionResult.Success("admin.applyban.permanent", mapOf("name" to name))
            }.getOrElse {
                support.logFailure("Failed to set permanent apply ban for $uuid.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun setApplyBanTemp(actor: Player?, uuid: UUID, name: String, until: OffsetDateTime): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_CANDIDATES_APPLYBAN)?.let { return it }
        if (!until.isAfter(OffsetDateTime.now())) {
            return ActionResult.Rejected("admin.applyban.temp_past")
        }
        return support.serializedResult("applyban:$uuid") {
            runCatching {
                withContext(Dispatchers.IO) {
                    electionRepository.setApplyBanTemp(uuid, name, until)
                }
                support.log(actor, "APPLY_BAN_TEMP", target = uuid.toString(), details = mapOf("name" to name, "until" to until.toString()))
                val days = Duration.between(OffsetDateTime.now(), until).toDays().coerceAtLeast(1)
                ActionResult.Success("admin.applyban.temp", mapOf("name" to name, "days" to days.toString()))
            }.getOrElse {
                support.logFailure("Failed to set temporary apply ban for $uuid.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun clearApplyBan(actor: Player?, uuid: UUID, name: String): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_CANDIDATES_APPLYBAN)?.let { return it }
        return support.serializedResult("applyban:$uuid") {
            runCatching {
                withContext(Dispatchers.IO) {
                    electionRepository.clearApplyBan(uuid)
                }
                support.log(actor, "APPLY_BAN_CLEAR", target = uuid.toString())
                ActionResult.Success("admin.applyban.cleared", mapOf("name" to name))
            }.getOrElse {
                support.logFailure("Failed to clear apply ban for $uuid.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun clearApplyBan(actor: Player?, uuid: UUID): ActionResult {
        val name = plugin.playerIdentities.displayName(uuid, uuid.toString())
        return clearApplyBan(actor, uuid, name)
    }

    private fun requireCandidateStatusPerm(
        actor: Player?,
        term: Int,
        uuid: UUID,
        target: CandidateStatus
    ): ActionResult? {
        val current = plugin.store.candidateEntry(term, uuid)?.status
        val required = when (target) {
            CandidateStatus.PROCESS -> Perms.ADMIN_CANDIDATES_PROCESS
            CandidateStatus.REMOVED -> Perms.ADMIN_CANDIDATES_REMOVE
            CandidateStatus.ACTIVE -> {
                if (current == CandidateStatus.REMOVED) {
                    Perms.ADMIN_CANDIDATES_RESTORE
                } else {
                    Perms.ADMIN_CANDIDATES_PROCESS
                }
            }
        }
        return support.requirePerms(actor, required)
    }
}
