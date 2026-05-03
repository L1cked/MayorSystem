package mayorSystem.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import mayorSystem.MayorPlugin
import mayorSystem.config.Settings
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlayerDisplayNameServiceTest {
    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")

    @BeforeTest
    fun setUpBukkit() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getPlayer(uuid) } returns null
        every { Bukkit.isPrimaryThread() } returns true
        every { Bukkit.getOfflinePlayer(uuid) } returns mockk<OfflinePlayer>(relaxed = true) {
            every { name } returns "Alice"
        }
    }

    @AfterTest
    fun tearDownBukkit() {
        unmockkStatic(Bukkit::class)
    }

    @Test
    fun `display reward tag renders before MayorSystem names by default`() {
        val plugin = plugin(renderBeforeLuckPerms = true)
        val service = PlayerDisplayNameService(plugin, resolver(plugin))

        val resolved = service.resolveMayor(uuid, "Alice")

        assertEquals("[Mayor] Alice", resolved.plain)
        assertEquals(true, resolved.usesLuckPermsPrefix)
    }

    @Test
    fun `display reward tag renders before resolved names used by npc identities`() {
        val plugin = plugin(renderBeforeLuckPerms = true)
        val service = PlayerDisplayNameService(plugin, resolver(plugin))

        val resolved = service.resolve(uuid, "Alice")

        assertEquals("[Mayor] Alice", resolved.plain)
        assertEquals(true, resolved.usesLuckPermsPrefix)
    }

    @Test
    fun `display reward tag supports hex shorthand color`() {
        val plugin = plugin(renderBeforeLuckPerms = true, tagDisplay = "&#04b5ff[LEGEND]")
        val service = PlayerDisplayNameService(plugin, resolver(plugin))

        val resolved = service.resolve(uuid, "Alice")

        assertEquals("[LEGEND] Alice", resolved.plain)
        assertFalse(resolved.mini.contains("&#04b5ff", ignoreCase = true))
        assertEquals(true, resolved.usesLuckPermsPrefix)
    }

    @Test
    fun `display reward tag can be omitted when another display system handles it`() {
        val plugin = plugin(renderBeforeLuckPerms = false)
        val service = PlayerDisplayNameService(plugin, resolver(plugin))

        val resolved = service.resolve(uuid, "Alice")

        assertEquals("Alice", resolved.plain)
        assertEquals(false, resolved.usesLuckPermsPrefix)
    }

    @Test
    fun `display reward tag follows live DeluxeTags display after cache clear`() {
        val plugin = plugin(renderBeforeLuckPerms = true, tagDisplay = "&6[Mayor]")
        val source = FakeTagSource(display = "&#04b5ff[LEGEND]")
        val resolver = resolver(plugin, source)
        val service = PlayerDisplayNameService(plugin, resolver)

        assertEquals("[LEGEND] Alice", service.resolve(uuid, "Alice").plain)

        source.display = "&#FF0000[PRESIDENT]"
        resolver.clear(uuid)

        assertEquals("[PRESIDENT] Alice", service.resolve(uuid, "Alice").plain)
    }

    private fun plugin(renderBeforeLuckPerms: Boolean, tagDisplay: String = "&6[%title_name%]"): MayorPlugin {
        val config = YamlConfiguration().apply {
            set("title.name", "Mayor")
            set("title.player_prefix", "<gold><bold>%title_name%</bold></gold>")
            set("title.username_group_enabled", true)
            set("title.username_group", "mayor_current")
            set("title.chat_prefix", "<gold><bold>%title_name%</bold></gold> <dark_gray>>></dark_gray> ")
            set("display_reward.enabled", true)
            set("display_reward.tag.enabled", true)
            set("display_reward.tag.deluxe_tag_id", "mayor_current")
            set("display_reward.tag.display", tagDisplay)
            set("display_reward.tag.render_before_luckperms", renderBeforeLuckPerms)
            set("admin.display_reward.tracked_uuid", uuid.toString())
            set("admin.display_reward.tracked_tag_id", "mayor_current")
        }
        val settings = Settings.from(config)
        return mockk(relaxed = true) {
            every { this@mockk.config } returns config
            every { this@mockk.settings } returns settings
        }
    }

    private fun resolver(
        plugin: MayorPlugin,
        source: FakeTagSource = FakeTagSource()
    ): DisplayRewardTagResolver =
        DisplayRewardTagResolver(plugin, source, cacheTtlMs = 60_000L)

    private class FakeTagSource(
        var active: String? = "mayor_current",
        var display: String? = null
    ) : DisplayRewardTagResolver.LiveTagSource {
        override fun isPrimaryThread(): Boolean = true
        override fun onlinePlayer(uuid: UUID): org.bukkit.entity.Player? = null
        override fun activeTagId(uuid: UUID, player: org.bukkit.entity.Player?): String? = active
        override fun tagDisplay(uuid: UUID, player: org.bukkit.entity.Player?, tagId: String): String? = display
    }
}
