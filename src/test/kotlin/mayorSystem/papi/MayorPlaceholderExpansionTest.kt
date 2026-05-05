package mayorSystem.papi

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import mayorSystem.MayorPlugin
import mayorSystem.config.Settings
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.MayorStore
import mayorSystem.elections.TermService
import mayorSystem.service.NoDisplayRewardTagResolver
import mayorSystem.service.PlayerDisplayNameService
import org.bukkit.Bukkit

class MayorPlaceholderExpansionTest {
    private val store = mockk<MayorStore>()
    private val termService = mockk<TermService>()

    @BeforeTest
    fun setUpBukkit() {
        mockkStatic(Bukkit::class)
        every { Bukkit.isPrimaryThread() } returns true
        every { Bukkit.getPlayer(any<UUID>()) } returns null
    }

    @AfterTest
    fun tearDownBukkit() {
        unmockkStatic(Bukkit::class)
    }

    @Test
    fun `leaderboard placeholders preserve raw name and expose display name separately`() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000111")
        val plugin = mockk<MayorPlugin>(relaxed = true)
        val online = mockk<org.bukkit.entity.Player>(relaxed = true)
        val playerDisplayNames = PlayerDisplayNameService(plugin, NoDisplayRewardTagResolver)
        val expansion = MayorPlaceholderExpansion(plugin)
        val entry = CandidateEntry(uuid, "Alice", CandidateStatus.ACTIVE)

        every { online.name } returns "Citizen Alice"
        every { Bukkit.getPlayer(uuid) } returns online
        every { plugin.isReady() } returns true
        every { plugin.termService } returns termService
        every { plugin.store } returns store
        every { plugin.playerDisplayNames } returns playerDisplayNames
        every { termService.computeNow() } returns (0 to 1)
        every { store.topCandidates(1, 1, includeRemoved = false) } returns listOf(entry to 9)

        assertEquals("Alice", expansion.onPlaceholderRequest(null, "leaderboard_1_name"))
        assertEquals("Citizen Alice", expansion.onPlaceholderRequest(null, "leaderboard_1_display_name"))
        assertEquals("9", expansion.onPlaceholderRequest(null, "leaderboard_1_votes"))
    }

    @Test
    fun `title display placeholder supports hex shorthand color`() {
        val plugin = mockk<MayorPlugin>(relaxed = true)
        val settings = mockk<Settings>(relaxed = true)
        val expansion = MayorPlaceholderExpansion(plugin)

        every { plugin.isReady() } returns true
        every { plugin.settings } returns settings
        every { settings.resolvedTitlePlayerPrefix() } returns "&#04b5ff[Mayor]"

        val rendered = expansion.onPlaceholderRequest(null, "title_display").orEmpty()

        assertFalse(rendered.contains("&#04b5ff", ignoreCase = true))
    }
}
