package mayorSystem.util

import com.destroystokyo.paper.profile.PlayerProfile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import mayorSystem.MayorPlugin
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileResolverTest {
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
    fun `uncached bedrock style name is rejected without mojang lookup or fallback uuid`() {
        val server = serverWithNoCachedPlayers()
        val plugin = plugin(server)
        var error: String? = null
        var callbackUuid: UUID? = null

        ProfileResolver.resolve(
            plugin,
            ".acidejuice22",
            onError = { error = it },
            callback = { uuid, _ -> callbackUuid = uuid }
        )

        assertEquals(null, callbackUuid)
        assertEquals(true, error?.contains("Bedrock players must join once") == true)
        verify(exactly = 0) { server.createProfile(any<String>()) }
    }

    @Test
    fun `cached bedrock style name resolves to cached server identity`() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val cached = mockk<OfflinePlayer> {
            every { uniqueId } returns uuid
            every { name } returns ".acidejuice22"
        }
        val server = serverWithNoCachedPlayers().also {
            every { it.getOfflinePlayerIfCached(".acidejuice22") } returns cached
        }
        val plugin = plugin(server)
        var resolvedUuid: UUID? = null
        var resolvedName: String? = null

        ProfileResolver.resolve(
            plugin,
            ".acidejuice22",
            onError = { error("unexpected error: $it") },
            callback = { uuid, name ->
                resolvedUuid = uuid
                resolvedName = name
            }
        )

        assertEquals(uuid, resolvedUuid)
        assertEquals(".acidejuice22", resolvedName)
        verify(exactly = 0) { server.createProfile(any<String>()) }
    }

    @Test
    fun `valid uncached java name fails closed when mojang returns no uuid`() {
        val profile = mockk<PlayerProfile> {
            every { id } returns null
            every { name } returns "MissingPlayer"
        }
        every { profile.update() } returns CompletableFuture.completedFuture(profile)
        val server = serverWithNoCachedPlayers().also {
            every { it.createProfile("MissingPlayer") } returns profile
        }
        val plugin = plugin(server)
        var error: String? = null
        var callbackUuid: UUID? = null

        ProfileResolver.resolve(
            plugin,
            "MissingPlayer",
            onError = { error = it },
            callback = { uuid, _ -> callbackUuid = uuid }
        )

        assertNull(callbackUuid)
        assertEquals(true, error?.contains("player was not selected") == true)
    }

    private fun serverWithNoCachedPlayers(): Server =
        mockk(relaxed = true) {
            every { getPlayerExact(any()) } returns null as Player?
            every { getOfflinePlayerIfCached(any()) } returns null as OfflinePlayer?
        }

    private fun plugin(server: Server): MayorPlugin =
        mockk(relaxed = true) {
            every { this@mockk.server } returns server
            every { isEnabled } returns true
            every { logger } returns Logger.getLogger("ProfileResolverTest")
        }
}
