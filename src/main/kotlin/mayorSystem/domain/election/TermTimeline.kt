package mayorSystem.domain.election

import java.time.Duration
import java.time.Instant

data class TermSchedule(
    val firstTermStart: Instant,
    val termLength: Duration,
    val voteWindow: Duration,
    val electionAfterTermEnd: Boolean,
    val termStartOverrides: Map<Int, Instant> = emptyMap(),
    val pause: SchedulePause = SchedulePause.NONE
) {
    init {
        require(!termLength.isNegative && !termLength.isZero) { "termLength must be positive." }
        require(!voteWindow.isNegative) { "voteWindow must not be negative." }
    }
}

data class SchedulePause(
    val totalPaused: Duration,
    val startedAt: Instant?
) {
    companion object {
        val NONE = SchedulePause(Duration.ZERO, null)
    }
}

data class TermIndex(
    val current: Int,
    val election: Int
)

data class TermWindow(
    val term: Int,
    val termStart: Instant,
    val termEnd: Instant,
    val electionOpen: Instant,
    val electionClose: Instant
)

enum class ElectionOverride {
    OPEN,
    CLOSED,
    NONE
}

class TermCalculator {

    fun compute(
        now: Instant,
        schedule: TermSchedule,
        latestResolvedTerm: Int? = null
    ): TermIndex {
        val timeline = computeTimeline(now, schedule)
        return if (latestResolvedTerm != null && latestResolvedTerm > timeline.current) {
            TermIndex(latestResolvedTerm, latestResolvedTerm + 1)
        } else {
            timeline
        }
    }

    fun windowFor(term: Int, schedule: TermSchedule): TermWindow {
        val start = termStart(term, schedule)
        val end = start.plus(schedule.termLength)
        val electionClose = start
        val electionOpen = electionClose.minus(schedule.voteWindow)
        return TermWindow(term, start, end, electionOpen, electionClose)
    }

    fun isElectionOpen(
        now: Instant,
        term: Int,
        schedule: TermSchedule,
        override: ElectionOverride = ElectionOverride.NONE
    ): Boolean {
        return when (override) {
            ElectionOverride.OPEN -> true
            ElectionOverride.CLOSED -> false
            ElectionOverride.NONE -> {
                val window = windowFor(term, schedule)
                val effectiveNow = effectiveNow(now, schedule.pause)
                !effectiveNow.isBefore(window.electionOpen) && effectiveNow.isBefore(window.electionClose)
            }
        }
    }

    fun normalizeOverrides(schedule: TermSchedule): Map<Int, Instant> {
        return normalizeTermStartOverrides(schedule.termStartOverrides, schedule)
    }

    private fun normalizeTermStartOverrides(raw: Map<Int, Instant>, schedule: TermSchedule): Map<Int, Instant> {
        if (raw.isEmpty()) return emptyMap()
        val accepted = linkedMapOf<Int, Instant>()
        raw[0]?.let { accepted[0] = it }

        raw.entries
            .filter { it.key > 0 }
            .sortedBy { it.key }
            .forEach { (termIndex, start) ->
                val previousTermStart = termStartWithOverrides(
                    termIndex = termIndex - 1,
                    schedule = schedule,
                    overrides = accepted,
                    baseStart = accepted[0] ?: schedule.firstTermStart
                )
                if (start.isAfter(previousTermStart)) {
                    accepted[termIndex] = start
                }
            }

        return accepted
    }

    private fun computeTimeline(now: Instant, schedule: TermSchedule): TermIndex {
        val effectiveNow = effectiveNow(now, schedule.pause)
        val anchor0 = termStart(0, schedule)

        if (effectiveNow.isBefore(anchor0)) return TermIndex(-1, 0)

        val termLen = cycleLength(schedule)
        val anchors = buildAnchors(schedule)

        var activeAnchorIndex = 0
        while (activeAnchorIndex + 1 < anchors.size && !effectiveNow.isBefore(anchors[activeAnchorIndex + 1].second)) {
            activeAnchorIndex++
        }

        val (segmentTermIndex, segmentStart) = anchors[activeAnchorIndex]
        val elapsedMs = Duration.between(segmentStart, effectiveNow).toMillis().coerceAtLeast(0)
        val steps = (elapsedMs / termLen.toMillis()).coerceAtMost(Int.MAX_VALUE.toLong())
        val derivedCurrent = (segmentTermIndex.toLong() + steps).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val nextAnchorTermIndex = anchors.getOrNull(activeAnchorIndex + 1)?.first
        val current = if (nextAnchorTermIndex == null) {
            derivedCurrent
        } else {
            derivedCurrent.coerceAtMost(nextAnchorTermIndex - 1)
        }

        return TermIndex(current, current + 1)
    }

    private fun buildAnchors(schedule: TermSchedule): List<Pair<Int, Instant>> {
        val overrides = normalizeOverrides(schedule)
        val anchors = mutableListOf<Pair<Int, Instant>>()
        anchors += (0 to (overrides[0] ?: schedule.firstTermStart))
        overrides.entries
            .filter { it.key != 0 }
            .sortedBy { it.key }
            .forEach { anchors += (it.key to it.value) }
        return anchors
    }

    private fun termStart(termIndex: Int, schedule: TermSchedule): Instant {
        val overrides = normalizeOverrides(schedule)
        return termStartWithOverrides(termIndex, schedule, overrides, schedule.firstTermStart)
    }

    private fun termStartWithOverrides(
        termIndex: Int,
        schedule: TermSchedule,
        overrides: Map<Int, Instant>,
        baseStart: Instant
    ): Instant {
        if (termIndex <= 0) {
            return overrides[0] ?: baseStart
        }

        val best = overrides.keys.filter { it in 1..termIndex }.maxOrNull()
        if (best == null) {
            return baseStart.plus(cycleLength(schedule).multipliedBy(termIndex.toLong()))
        }

        val anchorStart = overrides[best]!!
        val deltaTerms = termIndex - best
        return anchorStart.plus(cycleLength(schedule).multipliedBy(deltaTerms.toLong()))
    }

    private fun cycleLength(schedule: TermSchedule): Duration =
        if (schedule.electionAfterTermEnd) schedule.termLength.plus(schedule.voteWindow) else schedule.termLength

    private fun effectiveNow(now: Instant, pause: SchedulePause): Instant {
        val paused = pause.totalPaused.toMillis().coerceAtLeast(0)
        val activePause = pause.startedAt
            ?.let { Duration.between(it, now).toMillis().coerceAtLeast(0) }
            ?: 0L
        return now.minusMillis(paused + activePause)
    }
}
