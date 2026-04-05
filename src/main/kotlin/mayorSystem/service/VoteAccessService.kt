package mayorSystem.service

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import java.time.Instant
import java.util.UUID

class VoteAccessService(private val plugin: MayorPlugin) {

    data class Denial(
        val messageKey: String,
        val placeholders: Map<String, String> = emptyMap()
    )

    fun currentElectionTerm(now: Instant = Instant.now()): Int =
        plugin.termService.computeCached(now).second

    fun isElectionOpen(now: Instant, term: Int): Boolean {
        return when (plugin.config.getString("admin.election_override.$term")?.uppercase()) {
            "OPEN" -> true
            "CLOSED" -> false
            else -> plugin.termService.isElectionOpen(now, term)
        }
    }

    fun voteAccessDenial(term: Int, voter: UUID, now: Instant = Instant.now()): Denial? {
        if (!isElectionOpen(now, term)) {
            return Denial("public.vote_closed")
        }
        if (plugin.store.hasVoted(term, voter) && !plugin.settings.allowVoteChange) {
            return Denial("public.vote_already")
        }
        return null
    }

    fun findCandidateByName(term: Int, candidateName: String): CandidateEntry? =
        plugin.store.candidates(term, includeRemoved = false)
            .firstOrNull { it.lastKnownName.equals(candidateName, ignoreCase = true) }

    fun availableCandidateNames(term: Int): List<String> =
        plugin.store.candidates(term, includeRemoved = false)
            .filter { it.status == CandidateStatus.ACTIVE }
            .map { it.lastKnownName }

    fun activeCandidate(term: Int, candidateId: UUID): CandidateEntry? =
        plugin.store.candidates(term, includeRemoved = false)
            .firstOrNull { it.uuid == candidateId && it.status == CandidateStatus.ACTIVE }
}
