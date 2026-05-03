package mayorSystem.service

import io.mockk.every
import io.mockk.mockk
import mayorSystem.MayorPlugin
import mayorSystem.config.Settings
import mayorSystem.messaging.DisplayTextParser
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayRewardTagResolverTest {
    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000456")

    @Test
    fun `live tag display wins over configured template`() {
        val plugin = plugin(configuredDisplay = "&6[Mayor]")
        val source = FakeTagSource(display = "&#04b5ff[LEGEND]")
        val resolver = DisplayRewardTagResolver(plugin, source)

        val prefix = resolver.resolvePrefix(uuid)

        assertEquals("[LEGEND]", prefix?.let(DisplayTextParser::plain))
    }

    @Test
    fun `configured display is fallback when live tag display is unavailable`() {
        val plugin = plugin(configuredDisplay = "&#04b5ff[LEGEND]")
        val source = FakeTagSource(display = null)
        val resolver = DisplayRewardTagResolver(plugin, source)

        val prefix = resolver.resolvePrefix(uuid)

        assertEquals("[LEGEND]", prefix?.let(DisplayTextParser::plain))
    }

    @Test
    fun `different active DeluxeTags tag suppresses MayorSystem tag prefix`() {
        val plugin = plugin(configuredDisplay = "&6[Mayor]")
        val source = FakeTagSource(active = "other_tag", display = "&c[Other]")
        val resolver = DisplayRewardTagResolver(plugin, source)

        assertNull(resolver.resolvePrefix(uuid))
    }

    @Test
    fun `stale cache is reused off main thread instead of touching live source`() {
        val plugin = plugin(configuredDisplay = "&6[Mayor]")
        val source = FakeTagSource(display = "&#04b5ff[LEGEND]")
        var now = 0L
        val resolver = DisplayRewardTagResolver(plugin, source, nowMs = { now }, cacheTtlMs = 1L)

        assertEquals("[LEGEND]", resolver.resolvePrefix(uuid)?.let(DisplayTextParser::plain))

        now = 2L
        source.primaryThread = false
        source.display = "&#FF0000[PRESIDENT]"

        assertEquals("[LEGEND]", resolver.resolvePrefix(uuid)?.let(DisplayTextParser::plain))
    }

    private fun plugin(configuredDisplay: String): MayorPlugin {
        val config = YamlConfiguration().apply {
            set("title.name", "Mayor")
            set("title.player_prefix", "<gold><bold>%title_name%</bold></gold>")
            set("title.username_group_enabled", true)
            set("title.username_group", "mayor_current")
            set("title.chat_prefix", "<gold><bold>%title_name%</bold></gold> <dark_gray>>></dark_gray> ")
            set("display_reward.enabled", true)
            set("display_reward.tag.enabled", true)
            set("display_reward.tag.deluxe_tag_id", "mayor_current")
            set("display_reward.tag.display", configuredDisplay)
            set("display_reward.tag.render_before_luckperms", true)
            set("admin.display_reward.tracked_uuid", uuid.toString())
            set("admin.display_reward.tracked_tag_id", "mayor_current")
        }
        val settings = Settings.from(config)
        return mockk(relaxed = true) {
            every { this@mockk.config } returns config
            every { this@mockk.settings } returns settings
        }
    }

    private class FakeTagSource(
        var primaryThread: Boolean = true,
        var active: String? = "mayor_current",
        var display: String? = null
    ) : DisplayRewardTagResolver.LiveTagSource {
        override fun isPrimaryThread(): Boolean = primaryThread
        override fun onlinePlayer(uuid: UUID): Player? = null
        override fun activeTagId(uuid: UUID, player: Player?): String? = active
        override fun tagDisplay(uuid: UUID, player: Player?, tagId: String): String? = display
    }
}
