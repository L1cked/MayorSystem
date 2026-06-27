package mayorSystem.npc

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.util.UUID
import mayorSystem.MayorPlugin
import mayorSystem.data.MayorStore
import mayorSystem.elections.TermService
import mayorSystem.config.Messages
import mayorSystem.security.Perms
import mayorSystem.ui.GuiManager
import mayorSystem.ui.menus.MainMenu
import org.bukkit.entity.Player
import kotlin.test.Test

class MayorNpcServiceTest {
    @Test
    fun `openMayorCard opens main menu fallback when no mayor is elected`() {
        val player = mockk<Player>(relaxed = true)
        val plugin = mockk<MayorPlugin>(relaxed = true)
        val termService = mockk<TermService>()
        val store = mockk<MayorStore>()
        val gui = mockk<GuiManager>(relaxed = true)
        val messages = mockk<Messages>(relaxed = true, relaxUnitFun = true)

        every { player.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000123")
        every { player.hasPermission(Perms.USE) } returns true
        every { plugin.termService } returns termService
        every { plugin.store } returns store
        every { plugin.gui } returns gui
        every { plugin.messages } returns messages
        every { termService.computeCached(any()) } returns (0 to 1)
        every { store.winner(0) } returns null
        every { gui.open(player, any()) } just runs

        MayorNpcService(plugin).openMayorCard(player)

        verify(exactly = 1) { gui.open(player, ofType(MainMenu::class)) }
        verify(exactly = 0) { messages.msg(player, "npc.no_mayor_elected", any<Map<String, String>>(), any()) }
    }
}
