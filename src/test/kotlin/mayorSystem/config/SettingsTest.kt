package mayorSystem.config

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {

    @Test
    fun `apply cost is rounded to cents and clamped non negative`() {
        val rounded = Settings.from(baseConfig().apply {
            set("apply.cost", 1.235)
        })
        val clamped = Settings.from(baseConfig().apply {
            set("apply.cost", -4.999)
        })

        assertEquals(1.24, rounded.applyCost, 0.000001)
        assertEquals(0.0, clamped.applyCost, 0.0)
    }

    private fun baseConfig(): YamlConfiguration =
        YamlConfiguration().apply {
            set("term.first_term_start", "2026-01-01T00:00:00Z")
        }
}
