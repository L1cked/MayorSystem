package mayorSystem.security

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.entity.Player

class PermsTest {

    @Test
    fun `admin panel access is granted by feature permissions`() {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission(Perms.ADMIN_ACCESS) } returns false
        every { player.hasPermission(Perms.ADMIN_ELECTION_START) } returns true

        assertTrue(Perms.canOpenAdminPanel(player))
        assertTrue(Perms.isAdmin(player))
    }

    @Test
    fun `admin panel access is denied without admin permissions`() {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission(any<String>()) } returns false

        assertFalse(Perms.canOpenAdminPanel(player))
        assertFalse(Perms.isAdmin(player))
    }
}
