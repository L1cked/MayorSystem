package mayorSystem.perks

import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import mayorSystem.MayorPlugin
import org.bukkit.entity.Player

class PerkServiceTest {

    @Test
    fun `resolveText returns raw text when no viewer is supplied`() {
        val service = PerkService(mockk(relaxed = true))
        setPapiMethod(service, placeholderMethod())

        assertEquals("<gold>%unsafe%</gold>", service.resolveText(null, "<gold>%unsafe%</gold>"))
    }

    @Test
    fun `resolveText sanitizes placeholder output for explicit viewer`() {
        val service = PerkService(mockk<MayorPlugin>(relaxed = true))
        val viewer = mockk<Player>(relaxed = true)
        every { viewer.name } returns "Alice"
        setPapiMethod(service, placeholderMethod())

        val resolved = service.resolveText(viewer, "<gold>%unsafe% %viewer%</gold>")

        assertEquals("<gold>boom Alice</gold>", resolved)
    }

    private fun setPapiMethod(service: PerkService, method: Method) {
        val field: Field = PerkService::class.java.getDeclaredField("papiSetPlaceholders")
        field.isAccessible = true
        field.set(service, method)
    }

    private fun placeholderMethod(): Method =
        TestPlaceholderApi::class.java.getMethod("setPlaceholders", Player::class.java, String::class.java)

    private object TestPlaceholderApi {
        @JvmStatic
        fun setPlaceholders(player: Player, raw: String): String {
            return raw
                .replace("%unsafe%", "<click:run_command:'/op me'><red>boom</red></click>")
                .replace("%viewer%", "<green>${player.name}</green>")
        }
    }
}
