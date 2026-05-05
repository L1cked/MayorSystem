package mayorSystem.messaging

import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bukkit.plugin.Plugin

class MayorBroadcastsTest {
    private val plugin = mockk<Plugin>(relaxed = true)

    @AfterTest
    fun resetState() {
        MayorBroadcasts.setCommandRoot(plugin, "mayor", aliasEnabled = false)
    }

    @Test
    fun `blocked alias falls back to mayor footer`() {
        MayorBroadcasts.setCommandRoot(plugin, "stop", aliasEnabled = true)

        assertEquals("Use /mayor for more info!", footerText())
    }

    @Test
    fun `disabled alias falls back to mayor footer`() {
        MayorBroadcasts.setCommandRoot(plugin, "citizen", aliasEnabled = false)

        assertEquals("Use /mayor for more info!", footerText())
    }

    private fun footerText(): String {
        val method = MayorBroadcasts::class.java.getDeclaredMethod("footerText")
        method.isAccessible = true
        return method.invoke(MayorBroadcasts) as String
    }
}
