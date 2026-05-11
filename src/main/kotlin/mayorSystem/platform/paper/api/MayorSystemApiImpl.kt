package mayorSystem.platform.paper.api

import mayorSystem.MayorPlugin
import mayorSystem.api.MayorAddonRegistration
import mayorSystem.api.MayorPerkSource
import mayorSystem.api.MayorSystemApi
import mayorSystem.api.snapshot.CandidateSnapshot
import mayorSystem.api.snapshot.DisplayRewardSnapshot
import mayorSystem.api.snapshot.MayorSnapshot
import mayorSystem.api.snapshot.MayorSystemSnapshot
import mayorSystem.api.snapshot.PerkSnapshot
import mayorSystem.api.snapshot.TermSnapshot
import mayorSystem.config.Settings
import mayorSystem.config.SystemGateOption
import mayorSystem.data.MayorStore
import mayorSystem.data.RequestStatus
import mayorSystem.elections.TermService
import mayorSystem.perks.PerkService
import mayorSystem.platform.paper.addon.AddonPerkSourceRegistry
import org.bukkit.plugin.Plugin
import java.time.Instant

internal interface MayorSystemApiDependencies {
    val ready: Boolean
    val settings: Settings
    val store: MayorStore
    val perks: PerkService
    val hasTermService: Boolean
    val termService: TermService
    val addonPerkSources: AddonPerkSourceRegistry
}

private class MayorPluginApiDependencies(private val plugin: MayorPlugin) : MayorSystemApiDependencies {
    override val ready: Boolean get() = plugin.isReady()
    override val settings: Settings get() = plugin.settings
    override val store: MayorStore get() = plugin.store
    override val perks: PerkService get() = plugin.perks
    override val hasTermService: Boolean get() = plugin.hasTermService()
    override val termService: TermService get() = plugin.termService
    override val addonPerkSources: AddonPerkSourceRegistry get() = plugin.addonPerkSources
}

class MayorSystemApiImpl internal constructor(private val deps: MayorSystemApiDependencies) : MayorSystemApi {

    constructor(plugin: MayorPlugin) : this(MayorPluginApiDependencies(plugin))

    override fun snapshot(): MayorSystemSnapshot {
        val current = currentTerm()
        val election = electionTerm()
        return MayorSystemSnapshot(
            generatedAt = Instant.now(),
            ready = deps.ready,
            currentTerm = current,
            electionTerm = election,
            currentMayor = currentMayor(),
            activePerks = activePerkSnapshots(),
            displayReward = displayRewardSnapshot()
        )
    }

    override fun currentMayor(): MayorSnapshot? {
        val term = currentTermIndexOrNull() ?: return null
        val mayor = deps.store.winner(term) ?: return null
        val name = deps.store.winnerName(term)?.takeIf { it.isNotBlank() } ?: "Unknown player"
        return MayorSnapshot(
            uuid = mayor,
            lastKnownName = name,
            term = term,
            perkIds = activePerkIds().toSet()
        )
    }

    override fun currentTerm(): TermSnapshot? {
        val term = currentTermIndexOrNull() ?: return null
        return termSnapshot(term)
    }

    override fun electionTerm(): TermSnapshot? {
        if (!deps.hasTermService) return null
        val term = deps.termService.computeNow().second
        return if (term < 0) null else termSnapshot(term)
    }

    override fun activePerkIds(): Set<String> {
        val term = currentTermIndexOrNull() ?: return emptySet()
        if (deps.settings.isBlocked(SystemGateOption.PERKS)) return emptySet()
        val mayor = deps.store.winner(term) ?: return emptySet()
        val chosen = deps.store.chosenPerks(term, mayor)
        if (chosen.isEmpty()) return emptySet()

        val preset = deps.perks.presetPerks()
        val requestsById = deps.store.listRequests(term).associateBy { it.id }
        val out = linkedSetOf<String>()
        for (perkId in chosen) {
            if (perkId.startsWith("custom:", ignoreCase = true)) {
                val reqId = perkId.substringAfter(':').toIntOrNull() ?: continue
                val req = requestsById[reqId] ?: continue
                if (req.status == RequestStatus.APPROVED) {
                    out += "custom:$reqId"
                }
            } else if (preset.containsKey(perkId)) {
                out += perkId
            }
        }
        return out.toSet()
    }

    override fun allPerkIds(): Set<String> = deps.perks.presetPerks().keys.toSet()

    override fun registerPerkSource(owner: Plugin, source: MayorPerkSource): MayorAddonRegistration =
        deps.addonPerkSources.register(owner, source)

    private fun currentTermIndexOrNull(): Int? {
        if (!deps.hasTermService) return null
        val term = deps.termService.computeNow().first
        return if (term < 0) null else term
    }

    private fun termSnapshot(term: Int): TermSnapshot {
        val times = deps.termService.timesFor(term)
        val now = Instant.now()
        val voteCounts = deps.store.voteCounts(term)
        val fakeVotes = deps.store.fakeVoteAdjustments(term)
        val candidates = deps.store.candidates(term, includeRemoved = true).map { entry ->
            CandidateSnapshot(
                uuid = entry.uuid,
                lastKnownName = entry.lastKnownName,
                status = entry.status.name,
                votes = voteCounts[entry.uuid] ?: 0,
                fakeVoteAdjustment = fakeVotes[entry.uuid] ?: 0,
                chosenPerkIds = deps.store.chosenPerks(term, entry.uuid).toSet(),
                bio = deps.store.candidateBio(term, entry.uuid)
            )
        }
        return TermSnapshot(
            index = term,
            termStart = times.termStart,
            termEnd = times.termEnd,
            electionOpen = times.electionOpen,
            electionClose = times.electionClose,
            electionCurrentlyOpen = deps.termService.isElectionOpen(now, term),
            candidates = candidates.toList()
        )
    }

    private fun activePerkSnapshots(): Set<PerkSnapshot> {
        val active = activePerkIds()
        if (active.isEmpty()) return emptySet()
        val preset = deps.perks.presetPerks()
        val currentTerm = currentTermIndexOrNull() ?: -1
        val requestsById = if (currentTerm >= 0) deps.store.listRequests(currentTerm).associateBy { it.id } else emptyMap()
        return active.mapNotNull { perkId ->
            if (perkId.startsWith("custom:", ignoreCase = true)) {
                val req = perkId.substringAfter(':').toIntOrNull()?.let { requestsById[it] } ?: return@mapNotNull null
                PerkSnapshot(perkId, "custom", req.title, enabled = true, custom = true)
            } else {
                val def = preset[perkId] ?: return@mapNotNull null
                PerkSnapshot(def.id, def.sectionId, def.displayNameMm, def.enabled, custom = false)
            }
        }.toSet()
    }

    private fun displayRewardSnapshot(): DisplayRewardSnapshot {
        val reward = deps.settings.displayReward
        return DisplayRewardSnapshot(
            enabled = reward.enabled,
            defaultMode = reward.defaultMode.name,
            rankEnabled = reward.rank.enabled,
            tagEnabled = reward.tag.enabled,
            targetTracks = reward.targets.tracks.mapValues { it.value.name }.toMap(),
            targetGroups = reward.targets.groups.mapValues { it.value.name }.toMap(),
            targetUsers = reward.targets.users.mapValues { it.value.name }.toMap()
        )
    }
}
