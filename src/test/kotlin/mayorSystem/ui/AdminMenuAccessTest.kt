package mayorSystem.ui

import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import mayorSystem.MayorPlugin
import mayorSystem.candidates.ui.ConfirmRemoveCandidateMenu
import mayorSystem.monitoring.ui.AdminHealthMenu
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminSettingsMenu
import org.bukkit.entity.Player

class AdminMenuAccessTest {

    @Test
    fun `confirm remove candidate menu is treated as admin restricted`() {
        val plugin = mockk<MayorPlugin>(relaxed = true)

        assertTrue(AdminMenuAccess.isAdminMenu(ConfirmRemoveCandidateMenu(plugin, 1, UUID.randomUUID())))
    }

    @Test
    fun `display permissions can open settings hub`() {
        val plugin = mockk<MayorPlugin>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission(Perms.ADMIN_NPC_MAYOR) } returns true

        assertTrue(AdminMenuAccess.canOpen(player, AdminSettingsMenu(plugin)))
    }

    @Test
    fun `reward permissions can open health menu for reward health route`() {
        val plugin = mockk<MayorPlugin>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission(Perms.ADMIN_REWARD_VIEW) } returns true

        assertTrue(AdminMenuAccess.canOpen(player, AdminHealthMenu(plugin)))
    }

    @Test
    fun `candidate removal confirm requires remove permission`() {
        val plugin = mockk<MayorPlugin>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission(Perms.ADMIN_CANDIDATES_REMOVE) } returns true

        assertTrue(AdminMenuAccess.canOpen(player, ConfirmRemoveCandidateMenu(plugin, 1, UUID.randomUUID())))
    }
}
