package mayorSystem.data.store

import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import mayorSystem.MayorPlugin
import mayorSystem.data.MayorStore
import org.bukkit.configuration.file.YamlConfiguration

class MayorStoreSafetyTest {
    @Test
    fun `invalid store type fails startup instead of falling back to sqlite`() {
        val dataFolder = createTempDirectory("mayorsystem-store-test-").toFile()
        try {
            assertFailsWith<IllegalStateException> {
                MayorStore(mockPlugin(dataFolder) {
                    set("data.store.type", "postgres")
                    set("data.store.sqlite.file", "fallback.db")
                })
            }

            assertFalse(File(dataFolder, "fallback.db").exists())
        } finally {
            dataFolder.deleteRecursively()
        }
    }

    @Test
    fun `mysql startup failure does not silently create sqlite fallback`() {
        val dataFolder = createTempDirectory("mayorsystem-store-test-").toFile()
        val store = MayorStore(mockPlugin(dataFolder) {
            set("data.store.type", "mysql")
            set("data.store.sqlite.file", "fallback.db")
            set("data.store.mysql.host", "127.0.0.1")
            set("data.store.mysql.port", 3306)
            set("data.store.mysql.database", "mayorsystem")
            set("data.store.mysql.user", "CHANGE_ME")
            set("data.store.mysql.password", "CHANGE_ME")
            set("data.store.mysql.use_ssl", false)
            set("data.store.mysql.params", "")
        })

        try {
            assertFalse(runBlocking { store.loadAsync() })
            assertFalse(File(dataFolder, "fallback.db").exists())
        } finally {
            store.shutdown()
            dataFolder.deleteRecursively()
        }
    }

    private fun mockPlugin(
        dataFolder: File,
        configure: YamlConfiguration.() -> Unit
    ): MayorPlugin {
        val config = YamlConfiguration().apply(configure)
        return mockk(relaxed = true) {
            every { this@mockk.config } returns config
            every { this@mockk.dataFolder } returns dataFolder
            every { this@mockk.logger } returns Logger.getLogger("MayorStoreSafetyTest")
        }
    }
}
