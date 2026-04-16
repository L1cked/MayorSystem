package mayorSystem.service

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mayorSystem.MayorPlugin
import mayorSystem.config.Settings
import mayorSystem.data.CandidateEntry
import mayorSystem.data.CandidateStatus
import mayorSystem.data.MayorStore
import mayorSystem.monitoring.AuditService
import mayorSystem.perks.PerkService
import mayorSystem.security.Perms
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration

class AdminActionsTest {
    @Test
    fun `resetElectionTerms clears election admin state and preserves configured first term start`() {
        val config = YamlConfiguration().apply {
            set("title.name", "Mayor")
            set("title.player_prefix", "<gold><bold>%title_name%</bold></gold>")
            set("title.username_group_enabled", false)
            set("title.username_group", "mayor_current")
            set("title.chat_prefix", "<gold><bold>%title_name%</bold></gold> <dark_gray>>></dark_gray> ")
            set("enabled", true)
            set("public_enabled", true)
            set("pause.enabled", true)
            set("pause.options", listOf("SCHEDULE", "ACTIONS", "PERKS", "MAYOR_NPC", "BROADCASTS"))
            set("term.length", "P14D")
            set("term.vote_window", "P3D")
            set("term.election_after_term_end", false)
            set("term.first_term_start", "2026-03-24T17:02:40.385332190-05:00")
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
            set("admin.election_override.2", "OPEN")
            set("admin.forced_mayor.2.uuid", "00000000-0000-0000-0000-000000000999")
            set("admin.forced_mayor.2.name", "Alice")
            set("admin.term_start_override.2", "2026-04-09T20:33:32.599487100Z")
            set("admin.mayor_vacant.2", true)
            set("admin.pause.total_ms", 1234L)
            set("admin.pause.started_at", "2026-04-09T20:00:00Z")
        }

        var settings = Settings.from(config)
        val store = mockk<MayorStore>(relaxed = true)
        val perks = mockk<PerkService>(relaxed = true, relaxUnitFun = true)
        val audit = mockk<AuditService>(relaxed = true)
        val plugin = mockk<MayorPlugin>(relaxed = true)

        every { plugin.config } returns config
        every { plugin.settings } answers { settings }
        every { plugin.store } returns store
        every { plugin.perks } returns perks
        every { plugin.audit } returns audit
        every { plugin.actionCoordinator } returns ActionCoordinator()
        every { plugin.logger } returns Logger.getLogger("AdminActionsTest")
        every { plugin.hasTermService() } returns false
        every { plugin.hasMayorNpc() } returns false
        every { plugin.hasMayorUsernamePrefix() } returns false
        every { plugin.saveConfig() } just runs
        every { plugin.reloadSettingsOnly() } answers {
            settings = Settings.from(config)
        }

        val result = AdminActions(plugin).resetElectionTerms(actor = null)

        assertTrue(result.isSuccess)
        assertEquals("2026-03-24T17:02:40.385332190-05:00", config.getString("term.first_term_start"))
        assertEquals(false, config.getBoolean("pause.enabled"))
        assertNull(config.getConfigurationSection("admin.election_override"))
        assertNull(config.getConfigurationSection("admin.forced_mayor"))
        assertNull(config.getConfigurationSection("admin.term_start_override"))
        assertNull(config.getConfigurationSection("admin.mayor_vacant"))
        assertNull(config.getConfigurationSection("admin.pause"))
        verify(exactly = 1) { store.resetTermData() }
        verify(exactly = 1) { perks.rebuildActiveEffectsForTerm(-1) }
    }

    @Test
    fun `resetElectionTerms falls back to bundled first term start when current config is invalid`() {
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
            set("term.first_term_start", "not-a-date")
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
        }

        var settings = Settings.from(
            YamlConfiguration().apply {
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
                set("term.first_term_start", "2026-01-01T00:00:00Z")
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
            }
        )
        val store = mockk<MayorStore>(relaxed = true)
        val perks = mockk<PerkService>(relaxed = true, relaxUnitFun = true)
        val audit = mockk<AuditService>(relaxed = true)
        val plugin = mockk<MayorPlugin>(relaxed = true)

        every { plugin.config } returns config
        every { plugin.settings } answers { settings }
        every { plugin.store } returns store
        every { plugin.perks } returns perks
        every { plugin.audit } returns audit
        every { plugin.actionCoordinator } returns ActionCoordinator()
        every { plugin.logger } returns Logger.getLogger("AdminActionsTest")
        every { plugin.hasTermService() } returns false
        every { plugin.hasMayorNpc() } returns false
        every { plugin.hasMayorUsernamePrefix() } returns false
        every { plugin.saveConfig() } just runs
        every { plugin.reloadSettingsOnly() } answers {
            settings = Settings.from(
                config.apply { set("term.first_term_start", config.getString("term.first_term_start")) }
            )
        }
        every { plugin.getResource("config.yml") } returns """
            term:
              first_term_start: '2026-02-01T00:00:00Z'
        """.trimIndent().byteInputStream()

        val result = AdminActions(plugin).resetElectionTerms(actor = null)

        assertTrue(result.isSuccess)
        assertEquals("2026-02-01T00:00:00Z", config.getString("term.first_term_start"))
    }

    @Test
    fun `setCandidateStatus requires restore permission when reviving removed candidate`() {
        val term = 2
        val candidate = UUID.fromString("00000000-0000-0000-0000-000000000111")
        val actorId = UUID.fromString("00000000-0000-0000-0000-000000000222")
        val store = mockk<MayorStore>(relaxed = true)
        val plugin = mockAdminActionsPlugin(store)
        val actor = mockk<Player>(relaxed = true)

        every { store.candidateEntry(term, candidate) } returns CandidateEntry(candidate, "Alice", CandidateStatus.REMOVED)
        every { actor.uniqueId } returns actorId
        every { actor.name } returns "Moderator"
        every { actor.hasPermission(Perms.ADMIN_CANDIDATES_PROCESS) } returns true
        every { actor.hasPermission(Perms.ADMIN_CANDIDATES_RESTORE) } returns false
        every { actor.hasPermission(Perms.ADMIN_CANDIDATES_REMOVE) } returns false

        val rejected = AdminActions(plugin).setCandidateStatus(actor, term, candidate, CandidateStatus.ACTIVE, "Alice")

        assertTrue(rejected is ActionResult.Rejected)
        assertEquals("errors.no_permission", rejected.key)
        verify(exactly = 0) { store.setCandidateStatus(any(), any(), any()) }

        every { actor.hasPermission(Perms.ADMIN_CANDIDATES_RESTORE) } returns true

        val restored = AdminActions(plugin).setCandidateStatus(actor, term, candidate, CandidateStatus.ACTIVE, "Alice")

        assertTrue(restored.isSuccess)
        verify(exactly = 1) { store.setCandidateStatus(term, candidate, CandidateStatus.ACTIVE) }
    }

    @Test
    fun `setCandidateStatus allows process permission to return process candidate to active`() {
        val term = 3
        val candidate = UUID.fromString("00000000-0000-0000-0000-000000000333")
        val actorId = UUID.fromString("00000000-0000-0000-0000-000000000444")
        val store = mockk<MayorStore>(relaxed = true)
        val plugin = mockAdminActionsPlugin(store)
        val actor = mockk<Player>(relaxed = true)

        every { store.candidateEntry(term, candidate) } returns CandidateEntry(candidate, "Bob", CandidateStatus.PROCESS)
        every { actor.uniqueId } returns actorId
        every { actor.name } returns "Moderator"
        every { actor.hasPermission(Perms.ADMIN_CANDIDATES_PROCESS) } returns true
        every { actor.hasPermission(Perms.ADMIN_CANDIDATES_RESTORE) } returns false
        every { actor.hasPermission(Perms.ADMIN_CANDIDATES_REMOVE) } returns false

        val result = AdminActions(plugin).setCandidateStatus(actor, term, candidate, CandidateStatus.ACTIVE, "Bob")

        assertTrue(result.isSuccess)
        verify(exactly = 1) { store.setCandidateStatus(term, candidate, CandidateStatus.ACTIVE) }
    }

    @Test
    fun `setCandidateStatus requires remove permission for removals`() {
        val term = 4
        val candidate = UUID.fromString("00000000-0000-0000-0000-000000000555")
        val actorId = UUID.fromString("00000000-0000-0000-0000-000000000666")
        val store = mockk<MayorStore>(relaxed = true)
        val plugin = mockAdminActionsPlugin(store)
        val actor = mockk<Player>(relaxed = true)

        every { store.candidateEntry(term, candidate) } returns CandidateEntry(candidate, "Cara", CandidateStatus.PROCESS)
        every { actor.uniqueId } returns actorId
        every { actor.name } returns "Moderator"
        every { actor.hasPermission(Perms.ADMIN_CANDIDATES_PROCESS) } returns true
        every { actor.hasPermission(Perms.ADMIN_CANDIDATES_RESTORE) } returns false
        every { actor.hasPermission(Perms.ADMIN_CANDIDATES_REMOVE) } returns false

        val rejected = AdminActions(plugin).setCandidateStatus(actor, term, candidate, CandidateStatus.REMOVED, "Cara")

        assertTrue(rejected is ActionResult.Rejected)
        assertEquals("errors.no_permission", rejected.key)
        verify(exactly = 0) { store.setCandidateStatus(any(), any(), CandidateStatus.REMOVED) }

        every { actor.hasPermission(Perms.ADMIN_CANDIDATES_REMOVE) } returns true

        val removed = AdminActions(plugin).setCandidateStatus(actor, term, candidate, CandidateStatus.REMOVED, "Cara")

        assertTrue(removed.isSuccess)
        verify(exactly = 1) { store.setCandidateStatus(term, candidate, CandidateStatus.REMOVED) }
    }

    private fun mockAdminActionsPlugin(store: MayorStore): MayorPlugin {
        val config = baseSettingsConfig()
        var settings = Settings.from(config)
        val audit = mockk<AuditService>(relaxed = true)
        return mockk(relaxed = true) {
            every { this@mockk.config } returns config
            every { this@mockk.settings } answers { settings }
            every { this@mockk.store } returns store
            every { this@mockk.audit } returns audit
            every { this@mockk.actionCoordinator } returns ActionCoordinator()
            every { this@mockk.logger } returns Logger.getLogger("AdminActionsTest")
            every { this@mockk.hasLeaderboardHologram() } returns false
            every { this@mockk.reloadSettingsOnly() } answers {
                settings = Settings.from(config)
            }
        }
    }

    private fun baseSettingsConfig(firstTermStart: String = "2026-01-01T00:00:00Z"): YamlConfiguration =
        YamlConfiguration().apply {
            set("title.name", "Mayor")
            set("title.player_prefix", "<gold><bold>%title_name%</bold></gold>")
            set("title.username_group_enabled", false)
            set("title.username_group", "mayor_current")
            set("title.chat_prefix", "<gold><bold>%title_name%</bold></gold> <dark_gray>>></dark_gray> ")
            set("enabled", true)
            set("public_enabled", true)
            set("pause.enabled", false)
            set("pause.options", listOf("SCHEDULE", "ACTIONS", "PERKS", "MAYOR_NPC", "BROADCASTS"))
            set("term.length", "P14D")
            set("term.vote_window", "P3D")
            set("term.election_after_term_end", false)
            set("term.first_term_start", firstTermStart)
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
        }
}
