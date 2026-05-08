package mayorSystem.data.store

import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import mayorSystem.MayorPlugin
import mayorSystem.config.TiePolicy
import mayorSystem.data.CandidateStatus
import org.bukkit.configuration.file.YamlConfiguration

class SqliteMayorStoreTest {

    @Test
    fun `setCandidateBio does not create candidate state when candidate is absent`() {
        withStore { store ->
            val candidate = UUID.fromString("00000000-0000-0000-0000-000000000777")

            store.setCandidateBio(termIndex = 0, candidate = candidate, bio = "shadow bio")

            assertNull(store.candidateEntry(0, candidate))
            assertEquals("", store.candidateBio(0, candidate))
        }
    }

    @Test
    fun `setCandidateBio ignores removed candidates`() {
        withStore { store ->
            val candidate = UUID.fromString("00000000-0000-0000-0000-000000000888")

            store.setCandidate(termIndex = 0, uuid = candidate, name = "Alice")
            store.setCandidateBio(termIndex = 0, candidate = candidate, bio = "before")
            store.setCandidateStatus(termIndex = 0, uuid = candidate, status = CandidateStatus.REMOVED)

            store.setCandidateBio(termIndex = 0, candidate = candidate, bio = "after")

            assertEquals("before", store.candidateBio(0, candidate))
            assertEquals(CandidateStatus.REMOVED, store.candidateEntry(0, candidate)?.status)
        }
    }

    @Test
    fun `invalid sqlite pragma values fall back safely`() {
        withStore(
            configure = {
                set("data.store.sqlite.journal_mode", "WAL; DROP TABLE candidates")
                set("data.store.sqlite.synchronous", "NORMAL; DROP TABLE votes")
                set("data.store.sqlite.busy_timeout_ms", -500)
            }
        ) { store ->
            val candidate = UUID.fromString("00000000-0000-0000-0000-000000000999")

            store.setCandidate(termIndex = 0, uuid = candidate, name = "Alice")

            assertEquals("Alice", store.candidateEntry(0, candidate)?.lastKnownName)
        }
    }

    @Test
    fun `malformed temporary apply bans are skipped during load`() {
        val dataFolder = createTempDirectory("mayorsystem-sqlite-test-").toFile()
        val db = File(dataFolder, "elections.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE apply_bans (" +
                        "uuid TEXT PRIMARY KEY, " +
                        "name TEXT, " +
                        "permanent INTEGER, " +
                        "until TEXT, " +
                        "created_at TEXT" +
                        ")"
                )
                st.execute(
                    "INSERT INTO apply_bans(uuid,name,permanent,until,created_at) VALUES(" +
                        "'00000000-0000-0000-0000-000000000123','Alice',0,NULL,'2026-01-01T00:00:00Z'" +
                        ")"
                )
            }
        }

        val store = SqliteMayorStore(mockPlugin(dataFolder))
        try {
            store.load()

            assertEquals(emptyList(), store.listApplyBans())
        } finally {
            store.shutdown()
            dataFolder.deleteRecursively()
        }
    }

    @Test
    fun `seeded random tie winner is stable regardless of candidate insertion order`() {
        val alice = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val bob = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val cara = UUID.fromString("00000000-0000-0000-0000-000000000003")

        fun pick(order: List<UUID>): UUID? {
            var winner: UUID? = null
            withStore { store ->
                order.forEach { uuid ->
                    store.setCandidate(termIndex = 0, uuid = uuid, name = uuid.toString())
                }
                winner = store.pickWinner(
                    termIndex = 0,
                    tiePolicy = TiePolicy.SEEDED_RANDOM,
                    seededRngSeed = 12345L
                )?.uuid
            }
            return winner
        }

        assertEquals(
            pick(listOf(alice, bob, cara)),
            pick(listOf(cara, bob, alice))
        )
    }

    @Test
    fun `addRequestIfUnderLimit allows only one concurrent insert at limit`() {
        withStore { store ->
            val candidate = UUID.fromString("00000000-0000-0000-0000-000000000321")
            val workers = 8
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(workers)
            val results = Collections.synchronizedList(mutableListOf<Int?>())

            repeat(workers) { index ->
                executor.submit {
                    start.await(5, TimeUnit.SECONDS)
                    results += store.addRequestIfUnderLimit(
                        termIndex = 0,
                        candidate = candidate,
                        title = "Title $index",
                        description = "Description $index",
                        limit = 1
                    )
                }
            }

            start.countDown()
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)

            assertEquals(1, results.count { it != null })
            assertEquals(1, store.requestCountForCandidate(0, candidate))
        }
    }

    private fun withStore(
        configure: YamlConfiguration.() -> Unit = {},
        block: (SqliteMayorStore) -> Unit
    ) {
        val dataFolder = createTempDirectory("mayorsystem-sqlite-test-").toFile()
        val store = SqliteMayorStore(mockPlugin(dataFolder, configure))
        try {
            store.load()
            block(store)
        } finally {
            store.shutdown()
            dataFolder.deleteRecursively()
        }
    }

    private fun mockPlugin(
        dataFolder: File,
        configure: YamlConfiguration.() -> Unit = {}
    ): MayorPlugin {
        val config = YamlConfiguration().apply {
            set("data.store.sqlite.file", "elections.db")
            set("data.store.sqlite.async_writes", false)
            set("data.store.sqlite.strict", true)
            set("data.store.sqlite.journal_mode", "WAL")
            set("data.store.sqlite.synchronous", "NORMAL")
            set("data.store.sqlite.busy_timeout_ms", 2000)
            configure()
        }
        return mockk(relaxed = true) {
            every { this@mockk.config } returns config
            every { this@mockk.dataFolder } returns dataFolder
        }
    }
}
