package mayorSystem.api.snapshot

import java.time.Instant
import java.util.UUID

data class MayorSystemSnapshot(
    val generatedAt: Instant,
    val ready: Boolean,
    val currentTerm: TermSnapshot?,
    val electionTerm: TermSnapshot?,
    val currentMayor: MayorSnapshot?,
    val activePerks: Set<PerkSnapshot>,
    val displayReward: DisplayRewardSnapshot
)

data class TermSnapshot(
    val index: Int,
    val termStart: Instant,
    val termEnd: Instant,
    val electionOpen: Instant,
    val electionClose: Instant,
    val electionCurrentlyOpen: Boolean,
    val candidates: List<CandidateSnapshot>
)

data class MayorSnapshot(
    val uuid: UUID,
    val lastKnownName: String,
    val term: Int,
    val perkIds: Set<String>
)

data class CandidateSnapshot(
    val uuid: UUID,
    val lastKnownName: String,
    val status: String,
    val votes: Int,
    val fakeVoteAdjustment: Int,
    val chosenPerkIds: Set<String>,
    val bio: String
)

data class PerkSnapshot(
    val id: String,
    val sectionId: String,
    val displayName: String,
    val enabled: Boolean,
    val custom: Boolean
)

data class DisplayRewardSnapshot(
    val enabled: Boolean,
    val defaultMode: String,
    val rankEnabled: Boolean,
    val tagEnabled: Boolean,
    val targetTracks: Map<String, String>,
    val targetGroups: Map<String, String>,
    val targetUsers: Map<String, String>
)
