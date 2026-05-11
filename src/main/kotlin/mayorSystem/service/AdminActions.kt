package mayorSystem.service

import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import mayorSystem.MayorPlugin
import mayorSystem.application.usecase.AdminUseCases
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.RequestStatus
import mayorSystem.rewards.DisplayRewardMode
import mayorSystem.rewards.DisplayRewardTargetType
import org.bukkit.entity.Player

class AdminActions(
    plugin: MayorPlugin,
    private val useCases: AdminUseCases = AdminUseCases(plugin)
) {
    private val candidateActions = useCases.candidates
    private val displayRewardActions = useCases.displayRewards
    private val electionActions = useCases.elections
    private val perkActions = useCases.perks
    private val settingsActions = useCases.settings

    suspend fun updateConfig(
        actor: Player?,
        path: String,
        value: Any?,
        reload: Boolean = true,
        successKey: String,
        successPlaceholders: Map<String, String> = emptyMap()
    ): ActionResult {
        return settingsActions.updateConfig(actor, path, value, reload, successKey, successPlaceholders)
    }

    suspend fun updatePerkConfig(
        actor: Player?,
        path: String,
        value: Any?,
        successKey: String,
        successPlaceholders: Map<String, String> = emptyMap()
    ): ActionResult {
        return perkActions.updatePerkConfig(actor, path, value, successKey, successPlaceholders)
    }

    suspend fun updateSettingsConfig(
        actor: Player?,
        path: String,
        value: Any?,
        successKey: String,
        successPlaceholders: Map<String, String> = emptyMap()
    ): ActionResult {
        return settingsActions.updateSettingsConfig(actor, path, value, successKey, successPlaceholders)
    }

    suspend fun updateConfig(actor: Player?, path: String, value: Any?, reload: Boolean = true): ActionResult =
        settingsActions.updateConfig(actor, path, value, reload)

    suspend fun updatePerkConfig(actor: Player?, path: String, value: Any?): ActionResult =
        perkActions.updatePerkConfig(actor, path, value)

    suspend fun updateSettingsConfig(actor: Player?, path: String, value: Any?): ActionResult =
        settingsActions.updateSettingsConfig(actor, path, value)

    suspend fun updateSettingsConfig(path: String, value: Any?): ActionResult =
        settingsActions.updateSettingsConfig(path, value)

    suspend fun toggleAllowVoteChange(actor: Player?): ActionResult {
        return settingsActions.toggleAllowVoteChange(actor)
    }

    suspend fun toggleStepdownAllowReapply(actor: Player?): ActionResult {
        return settingsActions.toggleStepdownAllowReapply(actor)
    }

    suspend fun updateDisplayRewardConfig(
        actor: Player?,
        path: String,
        value: Any?,
        successKey: String,
        successPlaceholders: Map<String, String> = emptyMap()
    ): ActionResult {
        return displayRewardActions.updateDisplayRewardConfig(actor, path, value, successKey, successPlaceholders)
    }

    suspend fun syncDisplayReward(actor: Player?): ActionResult {
        return displayRewardActions.syncDisplayReward(actor)
    }

    suspend fun setDisplayRewardDefaultMode(actor: Player?, mode: DisplayRewardMode): ActionResult =
        displayRewardActions.setDisplayRewardDefaultMode(actor, mode)

    suspend fun setDisplayRewardTarget(actor: Player?, type: DisplayRewardTargetType, target: String, mode: DisplayRewardMode): ActionResult {
        return displayRewardActions.setDisplayRewardTarget(actor, type, target, mode)
    }

    suspend fun removeDisplayRewardTarget(actor: Player?, type: DisplayRewardTargetType, target: String): ActionResult {
        return displayRewardActions.removeDisplayRewardTarget(actor, type, target)
    }

    fun inspectDisplayRewardTarget(actor: Player?, type: DisplayRewardTargetType, target: String): ActionResult {
        return displayRewardActions.inspectDisplayRewardTarget(actor, type, target)
    }

    fun inspectDisplayRewardTargetAsync(actor: Player?, type: DisplayRewardTargetType, target: String): CompletableFuture<ActionResult> {
        return displayRewardActions.inspectDisplayRewardTargetAsync(actor, type, target)
    }

    fun listDisplayRewardTargets(actor: Player?, type: DisplayRewardTargetType?): ActionResult {
        return displayRewardActions.listDisplayRewardTargets(actor, type)
    }

    suspend fun forceStartElectionNow(actor: Player?): ActionResult {
        return electionActions.forceStartElectionNow(actor)
    }

    suspend fun forceEndElectionNow(actor: Player?): ActionResult {
        return electionActions.forceEndElectionNow(actor)
    }

    suspend fun clearAllOverridesForTerm(
        actor: Player?,
        term: Int,
        requireCurrentElectionTerm: Boolean = false
    ): ActionResult {
        return electionActions.clearAllOverridesForTerm(actor, term, requireCurrentElectionTerm)
    }

    fun findCandidateByName(term: Int, name: String): CandidateEntry? {
        return candidateActions.findCandidateByName(term, name)
    }

    suspend fun setFakeVoteAdjustment(
        actor: Player?,
        term: Int,
        uuid: UUID,
        name: String,
        amount: Int
    ): ActionResult {
        return electionActions.setFakeVoteAdjustment(actor, term, uuid, name, amount)
    }

    suspend fun setForcedMayorWithPerks(
        actor: Player?,
        term: Int,
        uuid: UUID,
        name: String,
        perks: Set<String>
    ): ActionResult {
        return electionActions.setForcedMayorWithPerks(actor, term, uuid, name, perks)
    }

    suspend fun clearForcedMayor(actor: Player?, term: Int): ActionResult {
        return electionActions.clearForcedMayor(actor, term)
    }

    suspend fun resetElectionTerms(actor: Player?): ActionResult {
        return electionActions.resetElectionTerms(actor)
    }

    suspend fun setCandidateStatus(
        actor: Player?,
        term: Int,
        uuid: UUID,
        status: CandidateStatus,
        name: String
    ): ActionResult {
        return candidateActions.setCandidateStatus(actor, term, uuid, status, name)
    }

    suspend fun setCandidateStatus(actor: Player?, term: Int, uuid: UUID, status: CandidateStatus): ActionResult {
        return candidateActions.setCandidateStatus(actor, term, uuid, status)
    }

    suspend fun setRequestStatus(actor: Player?, term: Int, id: Int, status: RequestStatus): ActionResult {
        return perkActions.setRequestStatus(actor, term, id, status)
    }

    suspend fun setPerkSectionEnabled(actor: Player?, sectionId: String, enabled: Boolean): ActionResult {
        return perkActions.setPerkSectionEnabled(actor, sectionId, enabled)
    }

    suspend fun setPerkEnabled(actor: Player?, sectionId: String, perkId: String, enabled: Boolean): ActionResult {
        return perkActions.setPerkEnabled(actor, sectionId, perkId, enabled)
    }

    suspend fun setApplyBanPermanent(actor: Player?, uuid: UUID, name: String): ActionResult {
        return candidateActions.setApplyBanPermanent(actor, uuid, name)
    }

    suspend fun setApplyBanTemp(actor: Player?, uuid: UUID, name: String, until: OffsetDateTime): ActionResult {
        return candidateActions.setApplyBanTemp(actor, uuid, name, until)
    }

    suspend fun clearApplyBan(actor: Player?, uuid: UUID, name: String): ActionResult {
        return candidateActions.clearApplyBan(actor, uuid, name)
    }

    suspend fun clearApplyBan(actor: Player?, uuid: UUID): ActionResult =
        candidateActions.clearApplyBan(actor, uuid)

    suspend fun forceElectNowWithPerks(
        actor: Player?,
        term: Int,
        uuid: UUID,
        name: String,
        perks: Set<String>
    ): ActionResult {
        return electionActions.forceElectNowWithPerks(actor, term, uuid, name, perks)
    }

    suspend fun reload(actor: Player?): ActionResult {
        return settingsActions.reload(actor)
    }

    suspend fun refreshPerksAll(actor: Player?): ActionResult {
        return perkActions.refreshPerksAll(actor)
    }

    suspend fun refreshPerksPlayer(actor: Player?, target: Player): ActionResult {
        return perkActions.refreshPerksPlayer(actor, target)
    }
}
