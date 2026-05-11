package mayorSystem.application.usecase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mayorSystem.MayorPlugin
import mayorSystem.config.SystemGateOption
import mayorSystem.data.RequestStatus
import mayorSystem.data.repository.ElectionRepository
import mayorSystem.data.repository.MayorStoreElectionRepository
import mayorSystem.security.Perms
import mayorSystem.service.ActionResult
import org.bukkit.entity.Player

/**
 * Command-facing admin perk actions.
 *
 * Keeps existing config, request, refresh, audit, and permission behavior while
 * moving perk administration out of the broad AdminActions facade.
 */
class AdminPerkCommandActions(
    private val plugin: MayorPlugin,
    private val electionRepository: ElectionRepository = MayorStoreElectionRepository(plugin.store)
) {
    private val support = AdminActionSupport(plugin)

    private companion object {
        private const val KEY_PERK_CATALOG = "perk-catalog"
        private const val KEY_PERK_REFRESH = "perk-refresh"
    }

    suspend fun updatePerkConfig(
        actor: Player?,
        path: String,
        value: Any?,
        successKey: String,
        successPlaceholders: Map<String, String> = emptyMap()
    ): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_PERKS_CATALOG)?.let { return it }
        return support.serializedResult(KEY_PERK_CATALOG) {
            runCatching {
                support.updateConfig(actor, path, value, reload = false)
                reloadPerksOnly()
                ActionResult.Success(successKey, successPlaceholders)
            }.getOrElse {
                support.logFailure("Failed to update perk config '$path'.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun updatePerkConfig(actor: Player?, path: String, value: Any?): ActionResult =
        updatePerkConfig(actor, path, value, "admin.perks.catalog_updated")

    suspend fun setRequestStatus(actor: Player?, term: Int, id: Int, status: RequestStatus): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_PERKS_REQUESTS)?.let { return it }
        return support.serializedResult("request:$term:$id") {
            runCatching {
                withContext(Dispatchers.IO) {
                    electionRepository.setRequestStatus(term, id, status)
                }
                support.log(actor, "REQUEST_STATUS", term = term, target = id.toString(), details = mapOf("status" to status.name))
                val key = if (status == RequestStatus.APPROVED) "admin.perks.request_approved" else "admin.perks.request_denied"
                ActionResult.Success(key, mapOf("id" to id.toString()))
            }.getOrElse {
                support.logFailure("Failed to set request status for #$id.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun setPerkSectionEnabled(actor: Player?, sectionId: String, enabled: Boolean): ActionResult {
        return updatePerkConfig(
            actor,
            "perks.sections.$sectionId.enabled",
            enabled,
            "admin.perks.section_updated",
            mapOf("section" to sectionId, "state" to if (enabled) "ENABLED" else "DISABLED")
        )
    }

    suspend fun setPerkEnabled(actor: Player?, sectionId: String, perkId: String, enabled: Boolean): ActionResult {
        return updatePerkConfig(
            actor,
            "perks.sections.$sectionId.perks.$perkId.enabled",
            enabled,
            "admin.perks.perk_updated",
            mapOf("section" to sectionId, "perk" to perkId, "state" to if (enabled) "ENABLED" else "DISABLED")
        )
    }

    suspend fun refreshPerksAll(actor: Player?): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_PERKS_REFRESH)?.let { return it }
        return support.serializedResult(KEY_PERK_REFRESH) {
            runCatching {
                val count = plugin.perks.refreshAllOnlinePlayers()
                support.log(actor, "PERKS_REFRESH_ALL", details = mapOf("players" to count.toString()))
                ActionResult.Success("admin.perks.refresh_all", mapOf("count" to count.toString()))
            }.getOrElse {
                support.logFailure("Failed to refresh perks for all players.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun refreshPerksPlayer(actor: Player?, target: Player): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_PERKS_REFRESH)?.let { return it }
        return support.serializedResult("$KEY_PERK_REFRESH:${target.uniqueId}") {
            runCatching {
                plugin.perks.refreshPlayer(target)
                support.log(actor, "PERKS_REFRESH_PLAYER", target = target.uniqueId.toString(), details = mapOf("name" to target.name))
                ActionResult.Success("admin.perks.refresh_player", mapOf("name" to target.name))
            }.getOrElse {
                support.logFailure("Failed to refresh perks for player ${target.uniqueId}.", it)
                ActionResult.Failure()
            }
        }
    }

    private suspend fun reloadPerksOnly() {
        support.onMain {
            plugin.perks.reloadFromConfig()
            if (plugin.hasTermService()) {
                val term = if (plugin.settings.isBlocked(SystemGateOption.PERKS)) -1 else plugin.termService.computeNow().first
                plugin.perks.rebuildActiveEffectsForTerm(term)
            }
        }
    }

}
