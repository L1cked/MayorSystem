package mayorSystem.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DisplayTextParserTest {

    @Test
    fun `ampersand hex shorthand renders without leaking raw marker`() {
        val raw = "&#04b5ff[LEGEND]"

        assertEquals("[LEGEND]", DisplayTextParser.plain(raw))
        assertFalse(DisplayTextParser.mini(raw).contains("&#04b5ff", ignoreCase = true))
    }

    @Test
    fun `uppercase ampersand hex shorthand renders display text`() {
        val raw = "&#FF0000[PRESIDENT] offline"

        assertEquals("[PRESIDENT] offline", DisplayTextParser.plain(raw))
        assertFalse(DisplayTextParser.mini(raw).contains("&#FF0000", ignoreCase = true))
    }

    @Test
    fun `classic ampersand legacy color renders display text`() {
        assertEquals("[Mayor]", DisplayTextParser.plain("&6[Mayor]"))
    }

    @Test
    fun `section legacy color renders display text`() {
        assertEquals("[Mayor]", DisplayTextParser.plain("\u00A76[Mayor]"))
    }

    @Test
    fun `unusual repeated hex legacy color renders display text`() {
        assertEquals("[LEGEND]", DisplayTextParser.plain("&x&0&4&b&5&f&f[LEGEND]"))
    }

    @Test
    fun `minimessage color renders display text`() {
        assertEquals("[LEGEND]", DisplayTextParser.plain("<#04b5ff>[LEGEND]"))
    }

    @Test
    fun `invalid minimessage falls back to escaped plain text`() {
        val plain = DisplayTextParser.plain("<not_a_real_tag>Admin</not_a_real_tag>")

        assertFalse(plain.contains("<"))
        assertFalse(plain.contains(">"))
    }
}
