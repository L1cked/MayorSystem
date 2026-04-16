package mayorSystem.messaging

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniMessageSafetyTest {
    private val mini = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()

    @Test
    fun `placeholder expansions cannot inject minimessage tags`() {
        val safe = MiniMessageSafety.applyPlaceholderApiSafely("<gold>Hello %prefix%%name%</gold>") { input ->
            input
                .replace("%prefix%", "<click:run_command:'/op me'><red>[Admin]</red></click> ")
                .replace("%name%", "<green>Alice</green>")
        }

        assertEquals("<gold>Hello [Admin] Alice</gold>", safe)
        assertEquals("Hello [Admin] Alice", plain.serialize(mini.deserialize(safe)))
    }
}
