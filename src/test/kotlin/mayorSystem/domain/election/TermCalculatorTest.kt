package mayorSystem.domain.election

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermCalculatorTest {
    private val calculator = TermCalculator()

    @Test
    fun `compute uses normalized overrides and caps before future anchor`() {
        val schedule = schedule(
            overrides = mapOf(
                1 to Instant.parse("2026-01-12T00:00:00Z"),
                3 to Instant.parse("2026-02-01T00:00:00Z")
            )
        )

        assertEquals(
            TermIndex(current = 2, election = 3),
            calculator.compute(Instant.parse("2026-01-31T12:00:00Z"), schedule)
        )
    }

    @Test
    fun `compute treats active pause time as frozen schedule time`() {
        val schedule = schedule(
            pause = SchedulePause(
                totalPaused = Duration.ofDays(1),
                startedAt = Instant.parse("2026-01-11T00:00:00Z")
            )
        )

        assertEquals(
            TermIndex(current = 0, election = 1),
            calculator.compute(Instant.parse("2026-01-12T00:00:00Z"), schedule)
        )
    }

    @Test
    fun `isElectionOpen respects explicit overrides before schedule math`() {
        val schedule = schedule()
        val outsideWindow = Instant.parse("2026-01-03T00:00:00Z")

        assertFalse(calculator.isElectionOpen(outsideWindow, 1, schedule))
        assertTrue(calculator.isElectionOpen(outsideWindow, 1, schedule, ElectionOverride.OPEN))
        assertFalse(calculator.isElectionOpen(outsideWindow, 1, schedule, ElectionOverride.CLOSED))
    }

    @Test
    fun `windowFor keeps vote window before term start`() {
        val schedule = schedule()
        val window = calculator.windowFor(1, schedule)

        assertEquals(Instant.parse("2026-01-11T00:00:00Z"), window.termStart)
        assertEquals(Instant.parse("2026-01-21T00:00:00Z"), window.termEnd)
        assertEquals(Instant.parse("2026-01-09T00:00:00Z"), window.electionOpen)
        assertEquals(Instant.parse("2026-01-11T00:00:00Z"), window.electionClose)
    }

    private fun schedule(
        overrides: Map<Int, Instant> = emptyMap(),
        pause: SchedulePause = SchedulePause.NONE
    ): TermSchedule =
        TermSchedule(
            firstTermStart = Instant.parse("2026-01-01T00:00:00Z"),
            termLength = Duration.ofDays(10),
            voteWindow = Duration.ofDays(2),
            electionAfterTermEnd = false,
            termStartOverrides = overrides,
            pause = pause
        )
}
