package mayorSystem.data.store

import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import mayorSystem.MayorPlugin
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

    private fun withStore(block: (SqliteMayorStore) -> Unit) {
        val dataFolder = createTempDirectory("mayorsystem-sqlite-test-").toFile()
        val store = SqliteMayorStore(mockPlugin(dataFolder))
        try {
            store.load()
            block(store)
        } finally {
            store.shutdown()
            dataFolder.deleteRecursively()
        }
    }

    private fun mockPlugin(dataFolder: File): MayorPlugin {
        val config = YamlConfiguration().apply {
            set("data.store.sqlite.file", "elections.db")
            set("data.store.sqlite.async_writes", false)
            set("data.store.sqlite.strict", true)
            set("data.store.sqlite.journal_mode", "WAL")
            set("data.store.sqlite.synchronous", "NORMAL")
            set("data.store.sqlite.busy_timeout_ms", 2000)
        }
        return mockk(relaxed = true) {
            every { this@mockk.config } returns config
            every { this@mockk.dataFolder } returns dataFolder
        }
    }
}
