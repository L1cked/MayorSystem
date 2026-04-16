package mayorSystem.config

import java.io.InputStreamReader
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ConfigDefaultsSyncTest {

    @Test
    fun `sync adds missing nested keys`() {
        val defaults = YamlConfiguration().apply {
            set("menus.status.timeline.lore.current_term", "<gray>Current term:</gray> <white>%term%</white>")
            set("menus.status.timeline.lore.next_term", "<gray>Next term:</gray> <white>#%term%</white>")
        }
        val yaml = YamlConfiguration().apply {
            set("menus.status.timeline.lore.next_term", "<gray>Legacy next term</gray>")
        }
        val file = createTempFile("gui-sync-", ".yml").toFile()

        val changed = ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults, null)

        assertTrue(changed)
        val reloaded = YamlConfiguration.loadConfiguration(file)
        assertEquals(
            "<gray>Current term:</gray> <white>%term%</white>",
            reloaded.getString("menus.status.timeline.lore.current_term")
        )
        assertEquals(
            "<gray>Legacy next term</gray>",
            reloaded.getString("menus.status.timeline.lore.next_term")
        )
    }

    @Test
    fun `sync replaces scalar parent with section when defaults require nesting`() {
        val defaults = YamlConfiguration().apply {
            set("menus.status.timeline.lore.current_term", "<gray>Current term:</gray> <white>%term%</white>")
        }
        val yaml = YamlConfiguration().apply {
            set("menus.status.timeline.lore", "<gray>Legacy scalar lore</gray>")
        }
        val file = createTempFile("gui-sync-", ".yml").toFile()

        val changed = ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults, null)

        assertTrue(changed)
        val reloaded = YamlConfiguration.loadConfiguration(file)
        assertTrue(reloaded.isConfigurationSection("menus.status.timeline.lore"))
        assertEquals(
            "<gray>Current term:</gray> <white>%term%</white>",
            reloaded.getString("menus.status.timeline.lore.current_term")
        )
    }

    @Test
    fun `sync fills null values from defaults`() {
        val defaults = YamlConfiguration().apply {
            set("menus.status.timeline.lore.current_term", "<gray>Current term:</gray> <white>%term%</white>")
        }
        val yaml = YamlConfiguration.loadConfiguration(
            """
            menus:
              status:
                timeline:
                  lore:
                    current_term:
            """.trimIndent().reader()
        )
        val file = createTempFile("gui-sync-", ".yml").toFile()

        val changed = ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults, null)

        assertTrue(changed)
        val reloaded = YamlConfiguration.loadConfiguration(file)
        assertEquals(
            "<gray>Current term:</gray> <white>%term%</white>",
            reloaded.getString("menus.status.timeline.lore.current_term")
        )
    }

    @Test
    fun `sync returns false when defaults are empty`() {
        val defaults = YamlConfiguration()
        val yaml = YamlConfiguration()
        val file = createTempFile("gui-sync-", ".yml").toFile()

        val changed = ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults, null)

        assertFalse(changed)
    }

    @Test
    fun `bundled config ships hardened storage defaults`() {
        val stream = javaClass.classLoader.getResourceAsStream("config.yml")
        assertNotNull(stream)

        val yaml = stream.use {
            YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8))
        }

        assertFalse(yaml.getBoolean("data.store.sqlite.async_writes", true))
        assertTrue(yaml.getBoolean("data.store.sqlite.strict", false))
        assertTrue(yaml.getBoolean("data.store.mysql.use_ssl", false))
        assertEquals("", yaml.getString("data.store.mysql.params"))
        assertEquals(0.9, yaml.getDouble("hologram.leaderboard.switching_y_offset"), 0.0001)
    }

    @Test
    fun `bundled defaults use display name placeholders by default`() {
        val configStream = javaClass.classLoader.getResourceAsStream("config.yml")
        val messagesStream = javaClass.classLoader.getResourceAsStream("messages.yml")
        assertNotNull(configStream)
        assertNotNull(messagesStream)

        val config = configStream.use {
            YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8))
        }
        val messages = messagesStream.use {
            YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8))
        }

        assertContains(
            config.getStringList("election.broadcast.vote.chat_lines").first(),
            "%player_display_name%"
        )
        assertContains(
            config.getString("election.broadcast.vote.title").orEmpty(),
            "%candidate_display_name%"
        )
        assertContains(
            config.getStringList("election.broadcast.apply.chat_lines").first(),
            "%player_display_name%"
        )
        assertContains(
            config.getStringList("election.broadcast.elected.chat_lines")[1],
            "%mayor_display_name%"
        )
        assertContains(
            config.getStringList("hologram.leaderboard.lines")[4],
            "%mayorsystem_leaderboard_1_display_name%"
        )
        assertContains(
            messages.getStringList("hologram.leaderboard.lines")[4],
            "%mayorsystem_leaderboard_1_display_name%"
        )
    }
}
