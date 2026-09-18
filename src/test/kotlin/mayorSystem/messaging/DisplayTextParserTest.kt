package mayorSystem.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `minimessage takes priority over legacy markers inside minimessage text`() {
        val rendered = DisplayTextParser.mini("<#04b5ff>[LEGEND] &l")

        assertFalse(rendered.contains("&#04b5ff", ignoreCase = true))
        assertEquals("[LEGEND] &l", DisplayTextParser.plain("<#04b5ff>[LEGEND] &l"))
    }

    @Test
    fun `invalid minimessage falls back to preserved plain text`() {
        val plain = DisplayTextParser.plain("<not_a_real_tag>Admin</not_a_real_tag>")

        assertEquals("<not_a_real_tag>Admin</not_a_real_tag>", plain)
    }

    @Test
    fun `plain comparison text preserves angle brackets`() {
        assertEquals("3 < 5 > 1", DisplayTextParser.plain("3 < 5 > 1"))
    }

    @Test
    fun `known nexo rank glyph renders with the pinned java font`() {
        val rendered = DisplayTextParser.mini("<glyph:rank_owner> <#67E8F9>L1cked")

        assertTrue(rendered.contains("nexo:default"))
        assertTrue(rendered.contains("\uE2B6"))
        assertFalse(rendered.contains("<glyph:rank_owner>"))
        assertEquals("[ᴏᴡɴᴇʀ] L1cked", DisplayTextParser.plain("<glyph:rank_owner> <#67E8F9>L1cked"))
    }

    @Test
    fun `bedrock inventory titles replace a rendered nexo rank glyph with readable text`() {
        val javaTitle = DisplayTextParser.component(
            "<gradient:#f7971e:#ffd200>👑 <glyph:rank_owner> <#67E8F9>L1cked</gradient>"
        )
        val bedrockTitle = DisplayTextParser.inventoryTitle(javaTitle, bedrock = true)
        val serialized = DisplayTextParser.mini(bedrockTitle)

        assertEquals("👑 [ᴏᴡɴᴇʀ] L1cked", DisplayTextParser.plain(bedrockTitle))
        assertFalse(serialized.contains("nexo:default"))
        assertFalse(serialized.contains("\uE2B6"))
        assertFalse(serialized.contains("<glyph:"))
    }

    @Test
    fun `java inventory titles preserve the rendered nexo glyph`() {
        val title = DisplayTextParser.component("<glyph:rank_designer> KayKay")
        val javaTitle = DisplayTextParser.inventoryTitle(title, bedrock = false)

        assertTrue(DisplayTextParser.mini(javaTitle).contains("nexo:default"))
        assertTrue(DisplayTextParser.mini(javaTitle).contains("\uE2C6"))
    }
}
