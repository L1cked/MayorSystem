package mayorSystem.api

import io.mockk.every
import io.mockk.mockk
import mayorSystem.config.Settings
import mayorSystem.config.SystemGateOption
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.CustomPerkRequest
import mayorSystem.data.MayorStore
import mayorSystem.data.RequestStatus
import mayorSystem.elections.TermService
import mayorSystem.elections.TermTimes
import mayorSystem.perks.PerkDef
import mayorSystem.perks.PerkOrigin
import mayorSystem.perks.PerkService
import mayorSystem.platform.paper.addon.AddonPerkSourceRegistry
import mayorSystem.platform.paper.api.MayorSystemApiDependencies
import mayorSystem.platform.paper.api.MayorSystemApiImpl
import mayorSystem.rewards.DisplayRewardMode
import mayorSystem.rewards.DisplayRewardSettings
import mayorSystem.rewards.DisplayRewardTargets
import mayorSystem.rewards.RankRewardSettings
import mayorSystem.rewards.TagRewardSettings
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MayorSystemApiImplTest {
    private val settings = mockk<Settings>()
    private val termService = mockk<TermService>()
    private val store = mockk<MayorStore>()
    private val perks = mockk<PerkService>()
    private lateinit var registry: AddonPerkSourceRegistry

    @BeforeTest
    fun setUp() {
        registry = AddonPerkSourceRegistry(
            config = { YamlConfiguration() },
            logger = java.util.logging.Logger.getLogger("MayorSystemApiImplTest"),
            saveConfig = {},
            reloadPerks = {}
        )
        every { settings.isBlocked(SystemGateOption.PERKS) } returns false
        every { settings.displayReward } returns displayReward()
        every { termService.computeNow() } returns (3 to 4)
        every { termService.timesFor(any()) } answers {
            val term = firstArg<Int>()
            TermTimes(
                termStart = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(term.toLong() * 86400L),
                termEnd = Instant.parse("2026-01-02T00:00:00Z").plusSeconds(term.toLong() * 86400L),
                electionOpen = Instant.parse("2025-12-31T00:00:00Z").plusSeconds(term.toLong() * 86400L),
                electionClose = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(term.toLong() * 86400L)
            )
        }
        every { termService.isElectionOpen(any(), any()) } returns false
        every { perks.presetPerks() } returns emptyMap()
        every { store.listRequests(any(), any()) } returns emptyList()
        every { store.listRequests(any()) } returns emptyList()
        every { store.winner(any()) } returns null
        every { store.winnerName(any()) } returns null
        every { store.candidates(any(), any()) } returns emptyList()
        every { store.voteCounts(any()) } returns emptyMap()
        every { store.fakeVoteAdjustments(any()) } returns emptyMap()
        every { store.chosenPerks(any(), any()) } returns emptySet()
        every { store.candidateBio(any(), any()) } returns ""
    }

    @Test
    fun `current term and mayor snapshots respect readiness and term availability`() {
        assertNull(api(hasTermService = false).currentTerm())

        every { termService.computeNow() } returns (-1 to 0)
        assertNull(api().currentTerm())

        val mayor = UUID.randomUUID()
        every { termService.computeNow() } returns (5 to 6)
        every { store.winner(5) } returns mayor
        every { store.winnerName(5) } returns "Alice"
        every { store.chosenPerks(5, mayor) } returns setOf("farming")
        every { perks.presetPerks() } returns mapOf("farming" to perk("farming"))

        val snapshot = api().currentMayor()
        assertEquals(mayor, snapshot?.uuid)
        assertEquals("Alice", snapshot?.lastKnownName)
        assertEquals(setOf("farming"), snapshot?.perkIds)
    }

    @Test
    fun `activePerkIds filters unknown and unapproved perks`() {
        val mayor = UUID.randomUUID()
        every { termService.computeNow() } returns (5 to 6)
        every { store.winner(5) } returns mayor
        every { store.chosenPerks(5, mayor) } returns setOf("farming", "missing", "Custom:1", "CUSTOM:2", "custom:bad")
        every { perks.presetPerks() } returns mapOf("farming" to perk("farming"))
        every { store.listRequests(5) } returns listOf(
            request(1, RequestStatus.APPROVED),
            request(2, RequestStatus.PENDING)
        )

        assertEquals(setOf("farming", "custom:1"), api().activePerkIds())
    }

    @Test
    fun `snapshot returns immutable active state DTO copies`() {
        val candidate = UUID.randomUUID()
        every { termService.computeNow() } returns (3 to 4)
        every { termService.isElectionOpen(any(), 4) } returns true
        every { store.candidates(4, true) } returns listOf(CandidateEntry(candidate, "Bob", CandidateStatus.ACTIVE))
        every { store.voteCounts(4) } returns mapOf(candidate to 6)
        every { store.fakeVoteAdjustments(4) } returns mapOf(candidate to 2)
        every { store.chosenPerks(4, candidate) } returns setOf("mining")
        every { store.candidateBio(4, candidate) } returns "Ready"

        val snapshot = api().snapshot()
        val electionTerm = assertNotNull(snapshot.electionTerm)

        assertTrue(snapshot.ready)
        assertEquals(4, electionTerm.index)
        assertTrue(electionTerm.electionCurrentlyOpen)
        assertEquals("Bob", electionTerm.candidates.single().lastKnownName)
        assertEquals(6, electionTerm.candidates.single().votes)
        assertEquals(2, electionTerm.candidates.single().fakeVoteAdjustment)
    }

    @Test
    fun `allPerkIds exposes configured preset ids without config sections`() {
        every { perks.presetPerks() } returns mapOf(
            "ore" to perk("ore"),
            "fishing" to perk("fishing")
        )

        assertEquals(setOf("ore", "fishing"), api().allPerkIds())
    }

    @Test
    fun `display reward snapshot exposes strings and maps only`() {
        val display = api().snapshot().displayReward

        assertEquals("RANK", display.defaultMode)
        assertTrue(display.rankEnabled)
        assertFalse(display.tagEnabled)
        assertEquals(mapOf("vip" to "TAG"), display.targetGroups)
    }

    private fun perk(id: String): PerkDef = PerkDef(
        id = id,
        displayNameMm = id,
        loreMm = emptyList(),
        adminLoreMm = emptyList(),
        icon = Material.STONE,
        onStart = emptyList(),
        onEnd = emptyList(),
        sectionId = "test",
        origin = PerkOrigin.INTERNAL,
        enabled = true
    )

    private fun request(id: Int, status: RequestStatus): CustomPerkRequest = CustomPerkRequest(
        id = id,
        candidate = UUID.randomUUID(),
        title = "Request $id",
        description = "Description $id",
        status = status,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        onStart = emptyList(),
        onEnd = emptyList()
    )

    private fun displayReward(): DisplayRewardSettings = DisplayRewardSettings(
        configured = true,
        enabled = true,
        defaultMode = DisplayRewardMode.RANK,
        adminViewPermission = "mayor.admin.reward.view",
        adminEditPermission = "mayor.admin.reward.edit",
        rank = RankRewardSettings(enabled = true),
        tag = TagRewardSettings(enabled = false),
        targets = DisplayRewardTargets(
            groups = mapOf("vip" to DisplayRewardMode.TAG)
        )
    )

    private fun api(hasTermService: Boolean = true): MayorSystemApiImpl =
        MayorSystemApiImpl(TestApiDependencies(hasTermService))

    private inner class TestApiDependencies(
        override val hasTermService: Boolean
    ) : MayorSystemApiDependencies {
        override val ready: Boolean get() = true
        override val settings: Settings get() = this@MayorSystemApiImplTest.settings
        override val store: MayorStore get() = this@MayorSystemApiImplTest.store
        override val perks: PerkService get() = this@MayorSystemApiImplTest.perks
        override val termService: TermService get() = this@MayorSystemApiImplTest.termService
        override val addonPerkSources: AddonPerkSourceRegistry get() = this@MayorSystemApiImplTest.registry
    }
}
