package mayorSystem.npc.provider

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.util.function.Consumer
import mayorSystem.MayorPlugin
import mayorSystem.npc.MayorNpcService
import org.bukkit.entity.Player
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FancyNpcsMayorNpcProviderTest {
    @Test
    fun `applyClickHandler attaches mayor card callback to restored npc data`() {
        val provider = FancyNpcsMayorNpcProvider()
        val plugin = mockk<MayorPlugin>(relaxed = true)
        val mayorNpc = mockk<MayorNpcService>(relaxed = true, relaxUnitFun = true)
        val player = mockk<Player>(relaxed = true)
        val data = FakeFancyNpcData()

        every { plugin.mayorNpc } returns mayorNpc
        every { mayorNpc.openMayorCard(player) } just runs
        FancyNpcsMayorNpcProvider::class.java.getDeclaredField("plugin").apply {
            isAccessible = true
            set(provider, plugin)
        }

        val method = FancyNpcsMayorNpcProvider::class.java.getDeclaredMethod("applyClickHandler", Any::class.java)
        method.isAccessible = true
        val attached = method.invoke(provider, data) as Boolean

        assertTrue(attached)
        val callback = assertNotNull(data.onClick)
        callback.accept(player)
        verify(exactly = 1) { mayorNpc.openMayorCard(player) }
    }

    private class FakeFancyNpcData {
        private var callback: Consumer<Player>? = null
        val onClick: Consumer<Player>?
            get() = callback

        fun setOnClick(callback: Consumer<Player>) {
            this.callback = callback
        }
    }
}
