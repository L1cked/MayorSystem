package mayorSystem.hologram

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mayorSystem.MayorPlugin
import mayorSystem.config.Settings
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.elections.TermTimes
import mayorSystem.service.PlayerDisplayNameService
import mayorSystem.showcase.ShowcaseMode
import mayorSystem.showcase.ShowcaseService
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration

class LeaderboardHologramServiceTest {

    @BeforeTest
    fun setUpBukkit() {
        mockkStatic(Bukkit::class)
    }

    @AfterTest
    fun tearDownBukkit() {
        unmockkStatic(Bukkit::class)
    }

    @Test
    fun `leaderboard hologram placeholders support display names`() {
        val plugin = mockPlugin()
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val entry = CandidateEntry(uuid, "Alice", CandidateStatus.ACTIVE)
        val service = LeaderboardHologramService(plugin)
        val online = mockk<org.bukkit.entity.Player>(relaxed = true)
        val displayNames = PlayerDisplayNameService(plugin)

        every { plugin.playerDisplayNames } returns displayNames
        every { online.name } returns "Citizen Alice"
        every { Bukkit.getPlayer(uuid) } returns online

        val method = LeaderboardHologramService::class.java.getDeclaredMethod(
            "applyMayorPlaceholders",
            String::class.java,
            Int::class.javaPrimitiveType,
            List::class.java,
            Int::class.javaPrimitiveType,
            TermTimes::class.java
        ).apply { isAccessible = true }

        val rendered = method.invoke(
            service,
            "%mayorsystem_leaderboard_1_name%|%mayorsystem_leaderboard_1_display_name%",
            0,
            listOf(entry to 7),
            1,
            null
        ) as String

        assertEquals("Alice|Citizen Alice", rendered)
    }

    @Test
    fun `legacy switching offset defaults are normalized to npc midpoint`() {
        val config = YamlConfiguration().apply {
            set("hologram.leaderboard.switching_y_offset", 2.8)
            set("npc.mayor.world", "world")
            set("npc.mayor.x", 10.0)
            set("npc.mayor.y", 64.0)
            set("npc.mayor.z", -4.0)
            set("npc.mayor.yaw", 0.0)
            set("npc.mayor.pitch", 0.0)
        }
        val plugin = mockPlugin(config)
        val showcase = mockk<ShowcaseService>()
        val world = mockk<World>(relaxed = true)
        val service = LeaderboardHologramService(plugin)

        every { plugin.showcase } returns showcase
        every { showcase.mode() } returns ShowcaseMode.SWITCHING
        every { Bukkit.getWorld("world") } returns world

        val method = LeaderboardHologramService::class.java.getDeclaredMethod("resolveLocation")
            .apply { isAccessible = true }
        val location = method.invoke(service) as org.bukkit.Location

        assertEquals(64.9, location.y, 0.0001)
        assertTrue(location.world === world)
    }

    @Test
    fun `individual hologram spawn anchor uses the same switching offset`() {
        val config = YamlConfiguration().apply {
            set("hologram.leaderboard.switching_y_offset", 0.9)
        }
        val plugin = mockPlugin(config)
        val world = mockk<World>(relaxed = true)
        val service = LeaderboardHologramService(plugin)

        val method = LeaderboardHologramService::class.java.getDeclaredMethod("toHologramAnchor", Location::class.java)
            .apply { isAccessible = true }
        val location = method.invoke(service, Location(world, 10.0, 64.0, -4.0, 0f, 0f)) as Location

        assertEquals(64.9, location.y, 0.0001)
        assertTrue(location.world === world)
    }

    private fun mockPlugin(config: YamlConfiguration = YamlConfiguration()): MayorPlugin {
        val settings = mockk<Settings>(relaxed = true)
        return mockk(relaxed = true) {
            every { this@mockk.config } returns config
            every { this@mockk.settings } returns settings
            every { settings.titleName } returns "Mayor"
            every { settings.titleNameLower() } returns "mayor"
        }
    }
}
