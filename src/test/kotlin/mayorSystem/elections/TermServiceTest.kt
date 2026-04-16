package mayorSystem.elections

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import mayorSystem.MayorPlugin
import mayorSystem.config.Settings
import mayorSystem.data.MayorStore
import mayorSystem.messaging.MayorBroadcasts
import mayorSystem.npc.MayorNpcService
import mayorSystem.perks.PerkService
import mayorSystem.service.PlayerDisplayNameService
import mayorSystem.service.MayorUsernamePrefixService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import io.mockk.verify

class TermServiceTest {
    private val store = mockk<MayorStore>(relaxed = true)
    private val perks = mockk<PerkService>(relaxed = true, relaxUnitFun = true)
    private val plain = PlainTextComponentSerializer.plainText()

    @BeforeTest
    fun setUpBukkit() {
        mockkStatic(Bukkit::class)
        every { Bukkit.isPrimaryThread() } returns true
        every { Bukkit.getPlayer(any<UUID>()) } returns null
        every { Bukkit.getOnlinePlayers() } returns mutableSetOf()
        every { Bukkit.getOfflinePlayer(any<UUID>()) } returns mockk<OfflinePlayer>(relaxed = true).also {
            every { it.name } returns "Offline"
        }
        every { store.highestWinnerTermOrNull() } returns null
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
    fun `resolveTermStartOverride clamps crossing next scheduled boundary`() {
        val service = termService(
            firstTermStart = OffsetDateTime.parse("2027-02-18T16:32:32.463414700-05:00"),
            overrides = arrayOf(
                "admin.term_start_override.0" to "2027-02-18T21:32:32.463414700Z",
                "admin.term_start_override.1" to "2027-02-18T22:34:53.587316100Z",
                "admin.term_start_override.4" to "2027-04-13T12:40:01.502514400Z"
            )
        )

        val resolution = resolveTermStartOverride(
            service = service,
            termIndex = 3,
            newStart = Instant.parse("2027-04-13T12:40:01.502514400Z")
        )

        requireNotNull(resolution)
        assertTrue(resolution.adjusted)
        assertEquals(
            Instant.parse("2027-04-13T12:40:01.501514400Z"),
            resolution.start
        )
        assertEquals("Clamped to stay before term 5.", resolution.note)
    }

    @Test
    fun `resolveTermStartOverride keeps valid requested start unchanged`() {
        val service = termService(
            firstTermStart = OffsetDateTime.parse("2027-02-18T16:32:32.463414700-05:00"),
            overrides = arrayOf(
                "admin.term_start_override.0" to "2027-02-18T21:32:32.463414700Z",
                "admin.term_start_override.1" to "2027-02-18T22:34:53.587316100Z",
                "admin.term_start_override.4" to "2027-04-13T12:40:01.502514400Z"
            )
        )

        val requested = Instant.parse("2027-03-31T12:00:00Z")
        val resolution = resolveTermStartOverride(
            service = service,
            termIndex = 3,
            newStart = requested
        )

        requireNotNull(resolution)
        assertFalse(resolution.adjusted)
        assertEquals(requested, resolution.start)
    }

    @Test
    fun `compute fast forwards to latest persisted winner term when schedule is stale`() {
        val service = termService()
        every { store.highestWinnerTermOrNull() } returns 1

        assertEquals(
            1 to 2,
            service.compute(Instant.parse("2026-03-01T12:00:00Z"))
        )
    }

    @Test
    fun `rebaseScheduleToCurrentTerm rewrites base start when latest winner is far ahead`() {
        val service = termService()
        val rebase = TermService::class.java.getDeclaredMethod(
            "rebaseScheduleToCurrentTerm",
            Int::class.javaPrimitiveType,
            Instant::class.java
        )
        rebase.isAccessible = true
        val requestedStart = Instant.parse("2026-04-07T14:00:00Z")

        rebase.invoke(service, 10, requestedStart)

        val plugin = readField(service, "plugin") as MayorPlugin
        assertEquals(
            "2025-11-18T09:00-05:00",
            plugin.config.getString("term.first_term_start")
        )
        assertEquals(
            null,
            plugin.config.getConfigurationSection("admin.term_start_override")
        )
    }

    @Test
    fun `sanitizeMayorVacancyConfig clears stale current vacancy when winner exists`() {
        val winner = UUID.fromString("00000000-0000-0000-0000-000000000101")
        every { store.winner(0) } returns winner

        val service = termService(
            "admin.mayor_vacant.0" to true
        )

        invokePrivateBoolean(
            service = service,
            methodName = "sanitizeMayorVacancyConfig",
            argTypes = arrayOf(Int::class.javaPrimitiveType!!),
            args = arrayOf(0)
        )

        val plugin = readField(service, "plugin") as MayorPlugin
        assertEquals(null, plugin.config.get("admin.mayor_vacant.0"))
    }

    @Test
    fun `normalizePauseState folds stale running pause into total when schedule pause is disabled`() {
        val now = Instant.parse("2026-04-09T12:00:00Z")
        val startedAt = now.minusSeconds(300)
        val service = termService(
            "pause.enabled" to false,
            "admin.pause.total_ms" to 1_000L,
            "admin.pause.started_at" to startedAt.toString()
        )

        invokePrivateBoolean(
            service = service,
            methodName = "normalizePauseState",
            argTypes = arrayOf(Instant::class.java),
            args = arrayOf(now)
        )

        val plugin = readField(service, "plugin") as MayorPlugin
        assertEquals(301_000L, plugin.config.getLong("admin.pause.total_ms"))
        assertEquals(null, plugin.config.getString("admin.pause.started_at"))
    }

    @Test
    fun `sanitizeTermStartOverrideConfig removes obsolete and invalid override entries`() {
        val service = termService(
            "admin.term_start_override.0" to "2026-02-18T21:32:32.463414700Z",
            "admin.term_start_override.1" to "2026-02-18T22:34:53.587316100Z",
            "admin.term_start_override.3" to "2026-03-30T12:40:01.502514400Z",
            "admin.term_start_override.6" to "2026-05-12T12:40:01.502514400Z",
            "admin.term_start_override.bad" to "nope",
            "admin.term_start_override.4" to "2026-03-30T12:27:55.080807600Z"
        )

        invokePrivateBoolean(
            service = service,
            methodName = "sanitizeTermStartOverrideConfig",
            argTypes = arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaObjectType),
            args = arrayOf(3, null)
        )

        val plugin = readField(service, "plugin") as MayorPlugin
        assertEquals(null, plugin.config.get("admin.term_start_override.0"))
        assertEquals(null, plugin.config.get("admin.term_start_override.1"))
        assertEquals(null, plugin.config.get("admin.term_start_override.4"))
        assertEquals(null, plugin.config.get("admin.term_start_override.bad"))
        assertEquals("2026-03-30T12:40:01.502514400Z", plugin.config.getString("admin.term_start_override.3"))
        assertEquals("2026-05-12T12:40:01.502514400Z", plugin.config.getString("admin.term_start_override.6"))
    }

    @Test
    fun `tickNow resumes partial current term activation when winner already exists`() = runBlocking {
        val winner = UUID.fromString("00000000-0000-0000-0000-000000000201")
        var activationComplete = false
        every { store.winner(0) } returns winner
        every { store.winnerName(0) } returns "Alice"
        every { store.highestWinnerTermOrNull() } returns 0
        every { store.mayorElectedAnnounced(0) } answers { activationComplete }
        every { store.setMayorElectedAnnounced(0, any()) } answers {
            activationComplete = secondArg()
        }

        val service = termService(
            firstTermStart = OffsetDateTime.now().minusDays(1).withNano(0),
            overrides = arrayOf(
                "enabled" to false,
                "enable_options" to listOf("MAYOR_NPC"),
                "election.broadcast.enabled" to false
            )
        )

        service.tickNow()

        verify(exactly = 1) { perks.applyPerks(0, any()) }
        verify(exactly = 1) { store.setMayorElectedAnnounced(0, true) }
        assertTrue(activationComplete)
    }

    @Test
    fun `tickNow reconciles schedule to latest persisted winner and activates that term`() = runBlocking {
        val currentMayor = UUID.fromString("00000000-0000-0000-0000-000000000301")
        val nextMayor = UUID.fromString("00000000-0000-0000-0000-000000000302")
        var activationComplete = false
        every { store.winner(0) } returns currentMayor
        every { store.winner(1) } returns null
        every { store.winner(2) } returns nextMayor
        every { store.winnerName(2) } returns "Bob"
        every { store.highestWinnerTermOrNull() } returns 2
        every { store.mayorElectedAnnounced(2) } answers { activationComplete }
        every { store.setMayorElectedAnnounced(2, any()) } answers {
            activationComplete = secondArg()
        }

        val service = termService(
            firstTermStart = OffsetDateTime.now().minusDays(1).withNano(0),
            overrides = arrayOf(
                "enabled" to false,
                "enable_options" to listOf("MAYOR_NPC"),
                "election.broadcast.enabled" to false,
                "admin.mayor_vacant.2" to true
            )
        )

        service.tickNow()

        val plugin = readField(service, "plugin") as MayorPlugin
        assertEquals(2, service.computeNow().first)
        assertEquals(null, plugin.config.get("admin.mayor_vacant.2"))
        verify(exactly = 1) { perks.clearPerks(0) }
        verify(exactly = 1) { perks.applyPerks(2, any()) }
        verify(exactly = 1) { store.setMayorElectedAnnounced(2, true) }
        assertTrue(activationComplete)
    }

    @Test
    fun `tickNow reconciles latest winner using effective schedule time when pause offset exists`() = runBlocking {
        val currentMayor = UUID.fromString("00000000-0000-0000-0000-000000000401")
        val nextMayor = UUID.fromString("00000000-0000-0000-0000-000000000402")
        var activationComplete = false
        every { store.winner(0) } returns currentMayor
        every { store.winner(1) } returns null
        every { store.winner(2) } returns nextMayor
        every { store.winnerName(2) } returns "Carol"
        every { store.highestWinnerTermOrNull() } returns 2
        every { store.mayorElectedAnnounced(2) } answers { activationComplete }
        every { store.setMayorElectedAnnounced(2, any()) } answers {
            activationComplete = secondArg()
        }

        val service = termService(
            firstTermStart = OffsetDateTime.now().minusDays(1).withNano(0),
            overrides = arrayOf(
                "enabled" to true,
                "pause.enabled" to false,
                "admin.pause.total_ms" to 209_432_515L,
                "enable_options" to listOf("MAYOR_NPC"),
                "election.broadcast.enabled" to false
            )
        )

        service.tickNow()

        val plugin = readField(service, "plugin") as MayorPlugin
        assertEquals(2, service.computeNow().first)
        val termTwoStart = plugin.config.getString("admin.term_start_override.2")
            ?: plugin.config.getString("term.first_term_start")
        requireNotNull(termTwoStart)
        assertTrue(Instant.parse(termTwoStart).isBefore(Instant.now()))
        verify(exactly = 1) { perks.applyPerks(2, any()) }
        verify(exactly = 1) { store.setMayorElectedAnnounced(2, true) }
        assertTrue(activationComplete)
    }

    @Test
    fun `clearAllOverridesForTerm also clears stale vacancy flag`() = runBlocking {
        val service = termService(
            "admin.election_override.2" to "OPEN",
            "admin.forced_mayor.2.uuid" to "00000000-0000-0000-0000-000000000999",
            "admin.forced_mayor.2.name" to "Alice",
            "admin.term_start_override.2" to "2026-04-09T20:33:32.599487100Z",
            "admin.mayor_vacant.2" to true
        )

        service.clearAllOverridesForTerm(2)

        val plugin = readField(service, "plugin") as MayorPlugin
        assertEquals(null, plugin.config.get("admin.election_override.2"))
        assertEquals(null, plugin.config.get("admin.forced_mayor.2"))
        assertEquals(null, plugin.config.get("admin.term_start_override.2"))
        assertEquals(null, plugin.config.get("admin.mayor_vacant.2"))
    }

    @Test
    fun `replaceBuiltins preserves mayor_name and adds mayor_display_name`() {
        val service = termService()
        val mayorUuid = UUID.fromString("00000000-0000-0000-0000-000000000777")

        val rendered = invokePrivateString(
            service = service,
            methodName = "replaceBuiltins",
            argTypes = arrayOf(
                String::class.java,
                Int::class.javaPrimitiveType!!,
                UUID::class.java,
                String::class.java,
                Map::class.java
            ),
            args = arrayOf(
                "%mayor_name%|%mayor_display_name%",
                4,
                mayorUuid,
                "Alice",
                emptyMap<String, String>()
            )
        )

        assertEquals("Alice|Mayor Alice", plain.serialize(MayorBroadcasts.deserialize(rendered)))
    }

    @Test
    fun `vote broadcast keeps legacy name placeholders raw and adds display placeholders`() {
        val voterUuid = UUID.fromString("00000000-0000-0000-0000-000000000888")
        val candidateUuid = UUID.fromString("00000000-0000-0000-0000-000000000889")
        val viewer = mockk<Player>(relaxed = true)
        val voter = mockk<Player>(relaxed = true)
        val candidate = mockk<Player>(relaxed = true)
        val sent = mutableListOf<Component>()
        every { viewer.sendMessage(capture(sent)) } just runs
        every { voter.name } returns "Citizen Alice"
        every { candidate.name } returns "Citizen Bob"
        every { Bukkit.getPlayer(voterUuid) } returns voter
        every { Bukkit.getPlayer(candidateUuid) } returns candidate
        every { Bukkit.getOnlinePlayers() } returns mutableSetOf(viewer)

        val service = termService(
            "election.broadcast.enabled" to true,
            "election.broadcast.vote.mode" to "CHAT",
            "election.broadcast.vote.chat_lines" to listOf(
                "<gray>%player_name%|%player_display_name%|%candidate_name%|%candidate_display_name%</gray>"
            )
        )

        service.broadcastVoteActivity(
            termIndex = 0,
            voterUuid = voterUuid,
            voterName = "Alice",
            candidateUuid = candidateUuid,
            candidateName = "Bob"
        )

        val payload = sent.map(plain::serialize).first { it.contains("Alice|") }
        assertEquals("Alice|Citizen Alice|Bob|Citizen Bob", payload)
    }

    private fun termService(vararg overrides: Pair<String, Any?>): TermService =
        termService(
            firstTermStart = OffsetDateTime.parse("2026-02-18T16:32:32.463414700-05:00"),
            overrides = overrides
        )

    private fun termService(
        firstTermStart: OffsetDateTime = OffsetDateTime.parse("2026-02-18T16:32:32.463414700-05:00"),
        overrides: Array<out Pair<String, Any?>>
    ): TermService {
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
            set("term.first_term_start", firstTermStart.toString())
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

        var settings = Settings.from(config)
        val plugin = mockk<MayorPlugin>(relaxed = true)
        val playerDisplayNames = PlayerDisplayNameService(plugin)
        every { plugin.config } returns config
        every { plugin.settings } answers { settings }
        every { plugin.store } returns store
        every { plugin.perks } returns perks
        every { plugin.playerDisplayNames } returns playerDisplayNames
        every { plugin.hasShowcase() } returns false
        every { plugin.hasMayorNpc() } returns false
        every { plugin.hasMayorUsernamePrefix() } returns false
        every { plugin.saveConfig() } just runs
        every { plugin.reloadSettingsOnly() } answers {
            settings = Settings.from(config)
        }
        every { plugin.logger } returns Logger.getLogger("MayorSystemTest")
        return TermService(plugin)
    }

    private fun resolveTermStartOverride(
        service: TermService,
        termIndex: Int,
        newStart: Instant
    ): ResolutionView? {
        val method = TermService::class.java.getDeclaredMethod(
            "resolveTermStartOverride",
            Int::class.javaPrimitiveType,
            Instant::class.java
        )
        method.isAccessible = true
        val result = method.invoke(service, termIndex, newStart) ?: return null
        val type = result.javaClass
        val start = type.getDeclaredField("start").apply { isAccessible = true }.get(result) as Instant
        val adjusted = type.getDeclaredField("adjusted").apply { isAccessible = true }.getBoolean(result)
        val note = type.getDeclaredField("note").apply { isAccessible = true }.get(result) as String?
        return ResolutionView(start = start, adjusted = adjusted, note = note)
    }

    private data class ResolutionView(
        val start: Instant,
        val adjusted: Boolean,
        val note: String?
    )

    private fun invokePrivateBoolean(
        service: TermService,
        methodName: String,
        argTypes: Array<Class<*>>,
        args: Array<Any?>
    ): Boolean {
        val method = TermService::class.java.getDeclaredMethod(methodName, *argTypes)
        method.isAccessible = true
        return method.invoke(service, *args) as Boolean
    }

    private fun invokePrivateString(
        service: TermService,
        methodName: String,
        argTypes: Array<Class<*>>,
        args: Array<Any?>
    ): String {
        val method = TermService::class.java.getDeclaredMethod(methodName, *argTypes)
        method.isAccessible = true
        return method.invoke(service, *args) as String
    }

    private fun readField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }
}
