package mayorSystem.integration

import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mayorSystem.integration.display.NoopHologramBackend
import mayorSystem.integration.display.NoopNpcBackend
import mayorSystem.integration.economy.NoopEconomyProvider
import mayorSystem.integration.permissions.NoopPermissionGroupProvider
import mayorSystem.integration.placeholder.NoopPlaceholderBridge
import mayorSystem.integration.tag.NoopDisplayTagProvider
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

class NoopIntegrationProvidersTest {
    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")

    @Test
    fun `noop economy fails closed for positive money and allows zero cost`() {
        val player = mockk<Player>(relaxed = true)

        assertFalse(NoopEconomyProvider.available)
        assertNull(NoopEconomyProvider.providerName)
        assertTrue(NoopEconomyProvider.has(player, 0.0))
        assertFalse(NoopEconomyProvider.has(player, 1.0))
        assertTrue(NoopEconomyProvider.withdraw(player, 0.0).success)
        assertFalse(NoopEconomyProvider.withdraw(player, 1.0).success)
        assertTrue(NoopEconomyProvider.deposit(player, 0.0).success)
        assertFalse(NoopEconomyProvider.deposit(player, 1.0).success)
        assertNull(NoopEconomyProvider.balance(player))
    }

    @Test
    fun `noop permission provider denies grants and treats removals as no-op success`() {
        assertFalse(NoopPermissionGroupProvider.available)
        assertFalse(NoopPermissionGroupProvider.groupExists("mayor"))
        assertFalse(NoopPermissionGroupProvider.trackExists("staff"))
        assertEquals(emptyList(), NoopPermissionGroupProvider.groupNames())
        assertEquals(emptyList(), NoopPermissionGroupProvider.trackNames())

        assertFalse(NoopPermissionGroupProvider.grantGroup(uuid, "Alice", "mayor").join().success)
        assertFalse(NoopPermissionGroupProvider.grantPermission(uuid, "Alice", "mayor.use").join().success)
        assertTrue(NoopPermissionGroupProvider.removeGroup(uuid, "Alice", "mayor").join().success)
        assertTrue(NoopPermissionGroupProvider.removePermission(uuid, "Alice", "mayor.use").join().success)
    }

    @Test
    fun `noop placeholder bridge returns input unchanged`() {
        val player = mockk<OfflinePlayer>(relaxed = true)

        assertFalse(NoopPlaceholderBridge.available)
        assertEquals("Hello %player_name%", NoopPlaceholderBridge.apply(player, "Hello %player_name%"))
        assertEquals("Hello %player_name%", NoopPlaceholderBridge.apply(null, "Hello %player_name%"))
    }

    @Test
    fun `noop display tag provider fails closed and never exposes tags`() {
        val caps = NoopDisplayTagProvider.capabilities

        assertFalse(caps.available)
        assertFalse(caps.canCreateTags)
        assertFalse(caps.canUpdateTags)
        assertFalse(caps.canSelectTags)
        assertFalse(caps.canClearTags)
        assertEquals(emptyList(), NoopDisplayTagProvider.loadedTagIds())
        assertNull(NoopDisplayTagProvider.tagSnapshot("mayor"))
        assertFalse(NoopDisplayTagProvider.ensureTag("mayor", "Mayor", "Mayor", 1).join().success)
        assertFalse(NoopDisplayTagProvider.selectTag(uuid, "Alice", "mayor").join().success)
        val clear = NoopDisplayTagProvider.clearTag(uuid, "Alice", "mayor").join()
        assertTrue(clear.success)
        assertTrue(clear.deferred)
    }

    @Test
    fun `noop display backends report unavailable and do not claim ownership`() {
        val location = mockk<Location>(relaxed = true)
        val entity = mockk<Entity>(relaxed = true)

        assertEquals("noop", NoopNpcBackend.id)
        assertFalse(NoopNpcBackend.available)
        NoopNpcBackend.enable()
        NoopNpcBackend.restoreFromConfig()
        NoopNpcBackend.spawnOrMove(location, "Admin")
        NoopNpcBackend.updateMayor(null)
        NoopNpcBackend.remove()
        NoopNpcBackend.disable()
        assertFalse(NoopNpcBackend.isOwnedEntity(entity))

        assertEquals("noop", NoopHologramBackend.id)
        assertFalse(NoopHologramBackend.available)
        assertEquals(listOf("A", "B"), NoopHologramBackend.formatLines(listOf("A", "B")))
        assertNull(NoopHologramBackend.get("mayor"))
        assertNull(NoopHologramBackend.create("mayor", location, persistent = false, lines = listOf("Mayor")))
        NoopHologramBackend.move(Any(), "mayor", location)
        NoopHologramBackend.setLines(Any(), listOf("Mayor"))
        NoopHologramBackend.remove("mayor")
    }
}
