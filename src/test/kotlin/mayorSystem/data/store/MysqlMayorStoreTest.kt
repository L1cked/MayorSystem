package mayorSystem.data.store

import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import mayorSystem.MayorPlugin
import org.bukkit.configuration.file.YamlConfiguration

class MysqlMayorStoreTest {

    @Test
    fun `mysql load rejects placeholder credentials before connecting`() {
        val dataFolder = createTempDirectory("mayorsystem-mysql-test-").toFile()
        val store = MysqlMayorStore(mockPlugin(dataFolder))

        try {
            assertFailsWith<IllegalStateException> {
                store.load()
            }
        } finally {
            store.shutdown()
            dataFolder.deleteRecursively()
        }
    }

    private fun mockPlugin(dataFolder: File): MayorPlugin {
        val config = YamlConfiguration().apply {
            set("data.store.mysql.host", "127.0.0.1")
            set("data.store.mysql.port", 3306)
            set("data.store.mysql.database", "mayorsystem")
            set("data.store.mysql.user", "CHANGE_ME")
            set("data.store.mysql.password", "CHANGE_ME")
            set("data.store.mysql.use_ssl", false)
            set("data.store.mysql.params", "")
        }
        return mockk(relaxed = true) {
            every { this@mockk.config } returns config
            every { this@mockk.dataFolder } returns dataFolder
        }
    }
}
