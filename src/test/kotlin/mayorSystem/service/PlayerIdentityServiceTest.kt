package mayorSystem.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import mayorSystem.MayorPlugin
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerIdentityServiceTest {
    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")

    @BeforeTest
    fun setUpBukkit() {
        mockkStatic(Bukkit::class)
    }

    @AfterTest
    fun tearDownBukkit() {
        unmockkStatic(Bukkit::class)
    }

    @Test
    fun `knownUuidByName resolves online player without offline fallback`() {
        val player = mockk<Player> {
            every { name } returns "Alice"
            every { uniqueId } returns uuid
        }
        every { Bukkit.getOnlinePlayers() } returns listOf(player)
        val plugin = pluginWithCachedEntries(emptyList())

        val resolved = PlayerIdentityService(plugin).knownUuidByName("alice")

        assertEquals(uuid, resolved)
        verify(exactly = 0) { Bukkit.getOfflinePlayer(any<String>()) }
    }

    @Test
    fun `knownUuidByName resolves trusted cached offline player`() {
        every { Bukkit.getOnlinePlayers() } returns emptyList()
        val plugin = pluginWithCachedEntries(
            listOf(OfflinePlayerCache.Entry(uuid, "Alice", hasPlayedBefore = true, isOnline = false))
        )

        val resolved = PlayerIdentityService(plugin).knownUuidByName("alice")

        assertEquals(uuid, resolved)
        verify(exactly = 0) { Bukkit.getOfflinePlayer(any<String>()) }
    }

    @Test
    fun `knownUuidByName rejects never joined name`() {
        every { Bukkit.getOnlinePlayers() } returns emptyList()
        val offline = mockk<OfflinePlayer> {
            every { hasPlayedBefore() } returns false
            every { uniqueId } returns uuid
        }
        every { Bukkit.getOfflinePlayer("MissingPlayer") } returns offline
        val plugin = pluginWithCachedEntries(emptyList())

        val resolved = PlayerIdentityService(plugin).knownUuidByName("MissingPlayer")

        assertNull(resolved)
    }

    @Test
    fun `displayName returns fallback before unknown player`() {
        every { Bukkit.getPlayer(uuid) } returns null
        val plugin = pluginWithCachedEntries(emptyList())

        val resolved = PlayerIdentityService(plugin).displayName(uuid, "Alice")

        assertEquals("Alice", resolved)
        verify(exactly = 0) { Bukkit.getOfflinePlayer(any<UUID>()) }
    }

    @Test
    fun `displayName falls back to cached offline name`() {
        every { Bukkit.getPlayer(uuid) } returns null
        val plugin = pluginWithCachedEntries(
            listOf(OfflinePlayerCache.Entry(uuid, "CachedAlice", hasPlayedBefore = true, isOnline = false))
        )

        val resolved = PlayerIdentityService(plugin).displayName(uuid)

        assertEquals("CachedAlice", resolved)
    }

    @Test
    fun `cachedDisplayName does not query offline profile fallback`() {
        every { Bukkit.getPlayer(uuid) } returns null
        val plugin = pluginWithCachedEntries(emptyList())

        val resolved = PlayerIdentityService(plugin).cachedDisplayName(uuid)

        assertEquals("Unknown player", resolved)
        verify(exactly = 0) { Bukkit.getOfflinePlayer(any<UUID>()) }
    }

    private fun pluginWithCachedEntries(entries: List<OfflinePlayerCache.Entry>): MayorPlugin {
        val cache = mockk<OfflinePlayerCache> {
            every { snapshot(forceRefresh = false) } returns OfflinePlayerCache.Snapshot(entries, refreshing = false, ageMs = 0L)
        }
        return mockk(relaxed = true) {
            every { offlinePlayers } returns cache
        }
    }
}
