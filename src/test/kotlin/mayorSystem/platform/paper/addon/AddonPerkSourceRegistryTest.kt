package mayorSystem.platform.paper.addon

import io.mockk.every
import io.mockk.mockk
import mayorSystem.api.MayorPerkDefinition
import mayorSystem.api.MayorPerkSection
import mayorSystem.api.MayorPerkSource
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.util.logging.Logger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonPerkSourceRegistryTest {
    private lateinit var config: YamlConfiguration
    private var saves = 0
    private var reloads = 0
    private lateinit var registry: AddonPerkSourceRegistry

    @BeforeTest
    fun setUp() {
        config = YamlConfiguration()
        saves = 0
        reloads = 0
        registry = AddonPerkSourceRegistry(
            config = { config },
            logger = Logger.getLogger("AddonPerkSourceRegistryTest"),
            saveConfig = { saves++ },
            reloadPerks = { reloads++ }
        )
    }

    @Test
    fun `register seeds missing section and close is idempotent`() {
        val registration = registry.register(owner("TestAddon"), source())

        assertEquals(true, config.getBoolean("perks.sections.test.enabled"))
        assertEquals("Test Section", config.getString("perks.sections.test.display_name"))
        assertEquals("Test Perk", config.getString("perks.sections.test.perks.speed.display_name"))
        assertEquals(1, saves)
        assertEquals(1, reloads)

        registration.close()
        registration.close()

        assertEquals(1, saves)
        assertEquals(1, reloads)
    }

    @Test
    fun `seeding preserves user values and fills only missing values`() {
        config.set("perks.sections.test.enabled", true)
        config.set("perks.sections.test.pick_limit", 9)
        config.set("perks.sections.test.display_name", "User Section")
        config.set("perks.sections.test.icon", "DIAMOND")
        config.set("perks.sections.test.perks.speed.enabled", false)
        config.set("perks.sections.test.perks.speed.display_name", "User Speed")

        val changed = registry.seedSources(listOf(source()))

        assertTrue(changed)
        assertEquals(9, config.getInt("perks.sections.test.pick_limit"))
        assertEquals("User Section", config.getString("perks.sections.test.display_name"))
        assertEquals("User Speed", config.getString("perks.sections.test.perks.speed.display_name"))
        assertEquals("FEATHER", config.getString("perks.sections.test.perks.speed.icon"))
        assertEquals(0, saves)
        assertEquals(0, reloads)
    }

    @Test
    fun `duplicate source ids and section ids are rejected`() {
        registry.register(owner("One"), source(sourceId = "shared"))

        assertFailsWith<IllegalArgumentException> {
            registry.register(owner("Two"), source(sourceId = "shared"))
        }

        assertFailsWith<IllegalArgumentException> {
            registry.seedSources(listOf(source(sourceId = "a"), source(sourceId = "b")))
        }
    }

    @Test
    fun `invalid ids are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            registry.register(owner("Bad"), source(sourceId = "Bad Source!"))
        }
        assertFailsWith<IllegalArgumentException> {
            registry.seedSources(listOf(source(sectionId = "-bad")))
        }
        assertFailsWith<IllegalArgumentException> {
            registry.seedSources(listOf(source(perkId = "bad perk")))
        }
    }

    @Test
    fun `unavailable source is registered but not seeded`() {
        registry.register(owner("Unavailable"), source(available = false))

        assertFalse(config.contains("perks.sections.test"))
        assertEquals(0, saves)
        assertEquals(0, reloads)
    }

    @Test
    fun `owner unregister removes active registration`() {
        val plugin = owner("Owner")
        registry.register(plugin, source(sourceId = "owner_source"))
        registry.unregisterOwner(plugin)

        registry.register(owner("Replacement"), source(sourceId = "owner_source"))
    }

    private fun owner(name: String): Plugin = mockk {
        every { this@mockk.name } returns name
        every { isEnabled } returns true
    }

    private fun source(
        sourceId: String = "test_source",
        sectionId: String = "test",
        perkId: String = "speed",
        available: Boolean = true
    ): MayorPerkSource = object : MayorPerkSource {
        override val id: String = sourceId
        override val displayName: String = "Test Source"
        override val available: Boolean = available

        override fun sections(): List<MayorPerkSection> = listOf(
            MayorPerkSection(
                id = sectionId,
                enabled = true,
                pickLimit = 1,
                displayName = "Test Section",
                icon = "EMERALD",
                perks = listOf(
                    MayorPerkDefinition(
                        id = perkId,
                        enabled = true,
                        displayName = "Test Perk",
                        icon = "FEATHER",
                        lore = listOf("Lore"),
                        adminLore = listOf("Admin"),
                        onStart = listOf("say start"),
                        onEnd = listOf("say end")
                    )
                )
            )
        )
    }
}
