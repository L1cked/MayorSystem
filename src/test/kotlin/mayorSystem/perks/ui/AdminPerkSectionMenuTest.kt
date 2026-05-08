package mayorSystem.perks.ui

import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertContains
import mayorSystem.MayorPlugin
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class AdminPerkSectionMenuTest {

    @Test
    fun `section title preserves literal angle brackets`() {
        val menu = AdminPerkSectionMenu(mockk<MayorPlugin>(relaxed = true), "<unsafe>")

        val plain = PlainTextComponentSerializer.plainText().serialize(menu.title)

        assertContains(plain, "<unsafe>")
    }
}
