package mayorSystem.api

import io.mockk.every
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import mayorSystem.MayorPlugin
import mayorSystem.config.Settings
import mayorSystem.config.SystemGateOption
import mayorSystem.data.CustomPerkRequest
import mayorSystem.data.MayorStore
import mayorSystem.data.RequestStatus
import mayorSystem.elections.TermService
import mayorSystem.perks.PerkDef
import mayorSystem.perks.PerkOrigin
import mayorSystem.perks.PerkService
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.time.OffsetDateTime
import java.util.UUID

class MayorSystemApiImplTest {
    private val settings = mockk<Settings>()
    private val termService = mockk<TermService>()
    private val store = mockk<MayorStore>()
    private val perks = mockk<PerkService>()
    private lateinit var config: YamlConfiguration

    @BeforeTest
    fun setUp() {
        config = YamlConfiguration()
        every { settings.isBlocked(SystemGateOption.PERKS) } returns false
        every { termService.computeNow() } returns (3 to 4)
        every { perks.presetPerks() } returns emptyMap()
        every { store.listRequests(any(), any()) } returns emptyList()
    }

    @Test
    fun `currentTermOrNull respects readiness and perk gating`() {
        assertNull(api(hasTermService = false).currentTermOrNull())

        every { settings.isBlocked(SystemGateOption.PERKS) } returns true
        assertNull(api().currentTermOrNull())

        every { settings.isBlocked(SystemGateOption.PERKS) } returns false
        every { termService.computeNow() } returns (-1 to 0)
        assertNull(api().currentTermOrNull())

        every { termService.computeNow() } returns (5 to 6)
        assertEquals(5, api().currentTermOrNull())
    }

    @Test
    fun `activePerkIdsForTerm filters unknown and unapproved perks`() {
        val mayor = UUID.randomUUID()
        every { store.winner(5) } returns mayor
        every { store.chosenPerks(5, mayor) } returns setOf("farming", "missing", "Custom:1", "CUSTOM:2", "custom:bad")
        every { perks.presetPerks() } returns mapOf("farming" to perk("farming"))
        every { store.listRequests(5, null) } returns listOf(
            request(1, RequestStatus.APPROVED),
            request(2, RequestStatus.PENDING)
        )

        assertEquals(setOf("farming", "custom:1"), api().activePerkIdsForTerm(5))
    }

    @Test
    fun `perkConfigSection returns configured preset perks only`() {
        config.set("perks.sections.farming.perks.wheat.enabled", true)
        config.set("perks.sections.mining.perks.ore.enabled", true)

        assertEquals(
            "perks.sections.farming.perks.wheat",
            api().perkConfigSection("wheat")?.currentPath
        )
        assertNull(api().perkConfigSection("custom:1"))
        assertNull(api().perkConfigSection("missing"))
    }

    @Test
    fun `allPerkIds merges config and preset ids without duplicates`() {
        config.set("perks.sections.farming.perks.wheat.enabled", true)
        config.set("perks.sections.mining.perks.ore.enabled", true)
        every { perks.presetPerks() } returns mapOf(
            "ore" to perk("ore"),
            "fishing" to perk("fishing")
        )

        assertEquals(setOf("wheat", "ore", "fishing"), api().allPerkIds())
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

    private fun api(hasTermService: Boolean = true): MayorSystemApiImpl =
        MayorSystemApiImpl(TestApiDependencies(hasTermService))

    private inner class TestApiDependencies(
        override val hasTermService: Boolean
    ) : MayorSystemApiDependencies {
        override val config: YamlConfiguration get() = this@MayorSystemApiImplTest.config
        override val settings: Settings get() = this@MayorSystemApiImplTest.settings
        override val store: MayorStore get() = this@MayorSystemApiImplTest.store
        override val perks: PerkService get() = this@MayorSystemApiImplTest.perks
        override val termService: TermService get() = this@MayorSystemApiImplTest.termService
    }
}
