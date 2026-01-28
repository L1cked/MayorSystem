package mayorSystem.service

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.RequestStatus
import org.bukkit.entity.Player
import java.time.OffsetDateTime
import java.util.UUID

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

    fun forceStartElectionNow(): Boolean = forceStartElectionNow(null)

    fun forceStartElectionNow(actor: Player?): Boolean {
        val ok = plugin.termService.forceStartElectionNow()
        log(actor, "ELECTION_FORCE_START", details = mapOf("ok" to ok.toString()))
        return ok
    }

    fun forceEndElectionNow(): Boolean = forceEndElectionNow(null)

    fun forceEndElectionNow(actor: Player?): Boolean {
        val ok = plugin.termService.forceEndElectionNow()
        log(actor, "ELECTION_FORCE_END", details = mapOf("ok" to ok.toString()))
        return ok
    }

    fun clearAllOverridesForTerm(term: Int) = clearAllOverridesForTerm(null, term)

    fun clearAllOverridesForTerm(actor: Player?, term: Int) {
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

    fun clearForcedMayor(term: Int) = clearForcedMayor(null, term)

    fun clearForcedMayor(actor: Player?, term: Int) {
        plugin.config.set("admin.forced_mayor.$term", null)
        plugin.saveConfig()
        log(actor, "ELECTION_FORCED_MAYOR_CLEAR", term = term)
    }

    fun setCandidateStatus(term: Int, uuid: UUID, status: CandidateStatus) =
        setCandidateStatus(null, term, uuid, status)

    fun setCandidateStatus(actor: Player?, term: Int, uuid: UUID, status: CandidateStatus) {
        plugin.store.setCandidateStatus(term, uuid, status)
        log(actor, "CANDIDATE_STATUS", term = term, target = uuid.toString(), details = mapOf("status" to status.name))
    }

    fun findCandidateByName(term: Int, name: String): CandidateEntry? {
        return plugin.store.candidates(term, includeRemoved = true)
            .firstOrNull { it.lastKnownName.equals(name, ignoreCase = true) }
    }

    fun setRequestStatus(term: Int, id: Int, status: RequestStatus) =
        setRequestStatus(null, term, id, status)

    fun setRequestStatus(actor: Player?, term: Int, id: Int, status: RequestStatus) {
        plugin.store.setRequestStatus(term, id, status)
        log(actor, "REQUEST_STATUS", term = term, target = id.toString(), details = mapOf("status" to status.name))
    }

    fun togglePerkSection(sectionId: String): Boolean {
        val path = "perks.sections.$sectionId.enabled"
        val enabled = plugin.config.getBoolean(path, true)
        updateConfig(path, !enabled)
        return !enabled
    }

    fun setPerkSectionEnabled(sectionId: String, enabled: Boolean) {
        updateConfig("perks.sections.$sectionId.enabled", enabled)
    }

    fun setPerkSectionEnabled(actor: Player?, sectionId: String, enabled: Boolean) {
        updateConfig(actor, "perks.sections.$sectionId.enabled", enabled)
    }

    fun togglePerk(sectionId: String, perkId: String): Boolean {
        val path = "perks.sections.$sectionId.perks.$perkId.enabled"
        val enabled = plugin.config.getBoolean(path, true)
        updateConfig(path, !enabled)
        return !enabled
    }

    fun setPerkEnabled(sectionId: String, perkId: String, enabled: Boolean) {
        updateConfig("perks.sections.$sectionId.perks.$perkId.enabled", enabled)
    }

    fun setPerkEnabled(actor: Player?, sectionId: String, perkId: String, enabled: Boolean) {
        if (enabled) {
            val base = "perks.sections.$sectionId.perks.$perkId"
            val hasMultiplier = plugin.config.contains("$base.sell_multiplier")
            val appliesTo = plugin.config.getString("$base.applies_to")?.uppercase()
            if (hasMultiplier && !plugin.perks.canEnableSellCategory(appliesTo)) {
                actor?.let { plugin.messages.msg(it, "admin.perks.sell_category_locked", mapOf("perk" to perkId)) }
                return
            }
        }
        updateConfig(actor, "perks.sections.$sectionId.perks.$perkId.enabled", enabled)
    }

    fun setApplyBanPermanent(uuid: UUID, name: String) = setApplyBanPermanent(null, uuid, name)

    fun setApplyBanPermanent(actor: Player?, uuid: UUID, name: String) {
        plugin.store.setApplyBanPermanent(uuid, name)
        log(actor, "APPLY_BAN_PERM", target = uuid.toString(), details = mapOf("name" to name))
    }

    fun setApplyBanTemp(uuid: UUID, name: String, until: OffsetDateTime) = setApplyBanTemp(null, uuid, name, until)

    fun setApplyBanTemp(actor: Player?, uuid: UUID, name: String, until: OffsetDateTime) {
        plugin.store.setApplyBanTemp(uuid, name, until)
        log(actor, "APPLY_BAN_TEMP", target = uuid.toString(), details = mapOf("name" to name, "until" to until.toString()))
    }

    fun clearApplyBan(uuid: UUID) = clearApplyBan(null, uuid)

    fun clearApplyBan(actor: Player?, uuid: UUID) {
        plugin.store.clearApplyBan(uuid)
        log(actor, "APPLY_BAN_CLEAR", target = uuid.toString())
    }

    fun forceElectNowWithPerks(term: Int, uuid: UUID, name: String, perks: Set<String>): Boolean =
        forceElectNowWithPerks(null, term, uuid, name, perks)

    fun forceElectNowWithPerks(actor: Player?, term: Int, uuid: UUID, name: String, perks: Set<String>): Boolean {
        plugin.store.setCandidate(term, uuid, name)
        plugin.store.setChosenPerks(term, uuid, perks)
        plugin.store.setPerksLocked(term, uuid, true)
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
