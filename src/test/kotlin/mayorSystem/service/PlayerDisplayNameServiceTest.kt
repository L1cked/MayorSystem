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
        val service = PlayerDisplayNameService(plugin)

        val resolved = service.resolveMayor(uuid, "Alice")

        assertEquals("[Mayor] Alice", resolved.plain)
        assertEquals(true, resolved.usesLuckPermsPrefix)
    }

    @Test
    fun `display reward tag renders before resolved names used by npc identities`() {
        val plugin = plugin(renderBeforeLuckPerms = true)
        val service = PlayerDisplayNameService(plugin)

        val resolved = service.resolve(uuid, "Alice")

        assertEquals("[Mayor] Alice", resolved.plain)
        assertEquals(true, resolved.usesLuckPermsPrefix)
    }

    @Test
    fun `display reward tag can be omitted when another display system handles it`() {
        val plugin = plugin(renderBeforeLuckPerms = false)
        val service = PlayerDisplayNameService(plugin)

        val resolved = service.resolve(uuid, "Alice")

        assertEquals("Alice", resolved.plain)
        assertEquals(false, resolved.usesLuckPermsPrefix)
    }

    private fun plugin(renderBeforeLuckPerms: Boolean): MayorPlugin {
        val config = YamlConfiguration().apply {
            set("title.name", "Mayor")
            set("title.player_prefix", "<gold><bold>%title_name%</bold></gold>")
            set("title.username_group_enabled", true)
            set("title.username_group", "mayor_current")
            set("title.chat_prefix", "<gold><bold>%title_name%</bold></gold> <dark_gray>>></dark_gray> ")
            set("display_reward.enabled", true)
            set("display_reward.tag.enabled", true)
            set("display_reward.tag.deluxe_tag_id", "mayor_current")
            set("display_reward.tag.display", "&6[%title_name%]")
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
}
