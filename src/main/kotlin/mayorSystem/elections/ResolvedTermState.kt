package mayorSystem.elections

import java.time.Instant

data class RuntimeTermState(
    val currentTermFloor: Int,
    val electionTermFloor: Int,
    val lastFinalizedTerm: Int,
    val anchorTermIndex: Int,
    val anchorStart: Instant? = null,
    val pauseTotalMs: Long = 0L,
    val pauseStartedAt: Instant? = null,
    val lastReconciledReason: String? = null,
    val updatedAt: Instant = Instant.now()
)

data class PauseState(
    val schedulePaused: Boolean,
    val totalMs: Long,
    val startedAt: Instant?
)

data class ResolvedTermState(
    val now: Instant,
    val effectiveNow: Instant,
    val currentTerm: Int,
    val electionTerm: Int,
    val currentTimes: TermTimes?,
    val electionTimes: TermTimes?,
    val pauseState: PauseState,
    val runtimeState: RuntimeTermState?,
    val highestWinnerTerm: Int?,
    val lastReconciledReason: String?
)
