package mayorSystem.api.snapshot

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MayorSnapshotsTest {
    @Test
    fun `snapshot DTOs expose read only collection contracts`() {
        assertEquals(Set::class.java, MayorSystemSnapshot::class.java.getDeclaredField("activePerks").type)
        assertEquals(List::class.java, TermSnapshot::class.java.getDeclaredField("candidates").type)
        assertEquals(Set::class.java, MayorSnapshot::class.java.getDeclaredField("perkIds").type)
        assertEquals(Set::class.java, CandidateSnapshot::class.java.getDeclaredField("chosenPerkIds").type)
        assertEquals(Map::class.java, DisplayRewardSnapshot::class.java.getDeclaredField("targetTracks").type)
        assertEquals(Map::class.java, DisplayRewardSnapshot::class.java.getDeclaredField("targetGroups").type)
        assertEquals(Map::class.java, DisplayRewardSnapshot::class.java.getDeclaredField("targetUsers").type)
    }

    @Test
    fun `snapshot api returns immutable active state DTOs without storage or config types`() {
        val mayor = MayorSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Alice", 2, setOf("wheat"))
        val candidate = CandidateSnapshot(
            uuid = mayor.uuid,
            lastKnownName = "Alice",
            status = "ACTIVE",
            votes = 4,
            fakeVoteAdjustment = 1,
            chosenPerkIds = setOf("wheat"),
            bio = "Hi"
        )
        val term = TermSnapshot(
            index = 2,
            termStart = Instant.parse("2026-01-01T00:00:00Z"),
            termEnd = Instant.parse("2026-01-15T00:00:00Z"),
            electionOpen = Instant.parse("2025-12-29T00:00:00Z"),
            electionClose = Instant.parse("2026-01-01T00:00:00Z"),
            electionCurrentlyOpen = false,
            candidates = listOf(candidate)
        )
        val display = DisplayRewardSnapshot(
            enabled = true,
            defaultMode = "RANK",
            rankEnabled = true,
            tagEnabled = false,
            targetTracks = mapOf("staff" to "RANK"),
            targetGroups = mapOf("vip" to "TAG"),
            targetUsers = mapOf(mayor.uuid.toString() to "BOTH")
        )
        val snapshot = MayorSystemSnapshot(
            generatedAt = Instant.parse("2026-01-02T00:00:00Z"),
            ready = true,
            currentTerm = term,
            electionTerm = null,
            currentMayor = mayor,
            activePerks = setOf(PerkSnapshot("wheat", "farming", "Wheat", true, false)),
            displayReward = display
        )

        assertTrue(snapshot.ready)
        assertEquals("Alice", snapshot.currentMayor?.lastKnownName)
        assertEquals(listOf(candidate), snapshot.currentTerm?.candidates)
        assertEquals(setOf("wheat"), snapshot.currentMayor?.perkIds)
        assertEquals(mapOf("vip" to "TAG"), snapshot.displayReward.targetGroups)
    }
}
