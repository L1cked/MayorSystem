package mayorSystem.messaging

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        assertTrue(safe.contains("\\<click:run_command:'/op me'>"))
        assertEquals(
            "Hello <click:run_command:'/op me'><red>[Admin]</red></click> <green>Alice</green>",
            plain.serialize(mini.deserialize(safe))
        )
    }

    @Test
    fun `untrusted minimessage escaping preserves literal angle brackets`() {
        val escaped = MiniMessageSafety.escapeUntrustedMiniMessage("Builder bio: <redstone> and <farmer> <")

        assertEquals("Builder bio: <redstone> and <farmer> <", plain.serialize(mini.deserialize(escaped)))
    }

    @Test
    fun `trusted formatting keeps color tags but blocks click tags`() {
        val escaped = MiniMessageSafety.sanitizeTrustedFormattingMiniMessage(
            "<dark_purple>[Mod]</dark_purple> <click:run_command:'/op me'>Alice</click>"
        )

        assertEquals(
            "[Mod] <click:run_command:'/op me'>Alice</click>",
            plain.serialize(mini.deserialize(escaped))
        )
    }
}
