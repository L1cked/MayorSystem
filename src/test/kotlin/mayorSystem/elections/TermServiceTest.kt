package mayorSystem.elections

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.lang.reflect.Field
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import mayorSystem.MayorPlugin
import mayorSystem.config.Settings
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin

class TermServiceTest {
    @BeforeTest
    fun setUpBukkit() {
        mockkStatic(Bukkit::class)
        every { Bukkit.isPrimaryThread() } returns true
    }

    @AfterTest
    fun tearDownBukkit() {
        unmockkStatic(Bukkit::class)
    }

    @Test
    fun `compute caps current term before future override starts`() {
        val service = termService(
            "admin.term_start_override.0" to "2026-02-18T21:32:32.463414700Z",
            "admin.term_start_override.1" to "2026-02-18T22:34:53.587316100Z",
            "admin.term_start_override.3" to "2026-03-30T12:40:01.502514400Z"
        )

        assertEquals(
            2 to 3,
            service.compute(Instant.parse("2026-03-27T12:00:00Z"))
        )
    }

    @Test
    fun `timesFor ignores invalid later override that moves backward in time`() {
        val service = termService(
            "admin.term_start_override.0" to "2026-02-18T21:32:32.463414700Z",
            "admin.term_start_override.1" to "2026-02-18T22:34:53.587316100Z",
            "admin.term_start_override.3" to "2026-03-30T12:40:01.502514400Z",
            "admin.term_start_override.4" to "2026-03-30T12:27:55.080807600Z"
        )

        assertEquals(
            Instant.parse("2026-04-13T12:40:01.502514400Z"),
            service.timesFor(4).termStart
        )
    }

    @Test
    fun `validateTermStartOverride rejects crossing next scheduled boundary`() {
        val service = termService(
            "admin.term_start_override.0" to "2026-02-18T21:32:32.463414700Z",
            "admin.term_start_override.1" to "2026-02-18T22:34:53.587316100Z",
            "admin.term_start_override.4" to "2026-04-13T12:40:01.502514400Z"
        )

        val validation = validateTermStartOverride(
            service = service,
            termIndex = 3,
            newStart = Instant.parse("2026-04-13T12:40:01.502514400Z")
        )

        assertNotNull(validation)
        assertEquals("Term start must stay before the next scheduled term boundary.", validation)
    }

    private fun termService(vararg overrides: Pair<String, String>): TermService {
        val config = YamlConfiguration().apply {
            set("title.name", "Mayor")
            set("title.player_prefix", "<gold><bold>%title_name%</bold></gold>")
            set("title.username_group_enabled", false)
            set("title.username_group", "mayor_current")
            set("title.chat_prefix", "<gold><bold>%title_name%</bold></gold> <dark_gray>>></dark_gray> ")
            set("enabled", true)
            set("public_enabled", true)
            set("pause.enabled", false)
            set("term.length", "P14D")
            set("term.vote_window", "P3D")
            set("term.election_after_term_end", false)
            set("term.first_term_start", "2026-02-18T16:32:32.463414700-05:00")
            set("term.perks_per_term", 2)
            set("term.bonus_term.enabled", false)
            set("term.bonus_term.every_x_terms", 4)
            set("term.bonus_term.perks_per_bonus_term", 3)
            set("apply.playtime_minutes", 0)
            set("apply.cost", 0.0)
            set("custom_requests.limit_per_term", 2)
            set("custom_requests.request_condition", "APPLY_REQUIREMENTS")
            set("election.allow_vote_change", true)
            set("election.tie_policy", "SEEDED_RANDOM")
            set("election.stepdown.allow_reapply", false)
            set("election.mayor_stepdown", "OFF")
            set("ux.chat_prompt_timeout_seconds", 300)
            set("ux.chat_prompts.max_length.bio", 50)
            set("ux.chat_prompts.max_length.title", 50)
            set("ux.chat_prompts.max_length.description", 50)
            for ((path, value) in overrides) {
                set(path, value)
            }
        }

        val settings = Settings.from(config)
        val plugin = allocate(MayorPlugin::class.java)
        setField(plugin, JavaPlugin::class.java, "newConfig", config)
        setField(plugin, MayorPlugin::class.java, "settings", settings)
        return TermService(plugin)
    }

    private fun validateTermStartOverride(
        service: TermService,
        termIndex: Int,
        newStart: Instant
    ): String? {
        val method = TermService::class.java.getDeclaredMethod(
            "validateTermStartOverride",
            Int::class.javaPrimitiveType,
            Instant::class.java
        )
        method.isAccessible = true
        return method.invoke(service, termIndex, newStart) as String?
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, type) as T
    }

    private fun setField(target: Any, owner: Class<*>, name: String, value: Any?) {
        val field: Field = owner.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
