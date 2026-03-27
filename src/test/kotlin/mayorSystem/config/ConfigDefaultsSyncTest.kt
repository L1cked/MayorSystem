package mayorSystem.config

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

        val changed = ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults)

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

        val changed = ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults)

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

        val changed = ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults)

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

        val changed = ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults)

        assertFalse(changed)
    }
}
