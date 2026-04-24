package mayorSystem.npc

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

class MayorNpcDisplayNamesTest {
    private val mini = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacySection()
    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")

    @Test
    fun `legacy npc name does not prepend title when LuckPerms prefix is already present`() {
        val identity = identity(
            displayName = Component.text("[Mayor]", NamedTextColor.GOLD)
                .append(Component.space())
                .append(Component.text("Alice", NamedTextColor.YELLOW)),
            displayNamePlain = "[Mayor] Alice",
            usesLuckPermsPrefix = true,
            titleLegacy = "Mayor"
        )

        assertEquals("\u00A76[Mayor] \u00A7eAlice", MayorNpcDisplayNames.legacy(identity, legacy))
    }

    @Test
    fun `legacy npc name prepends title for unprefixed fallback names`() {
        val identity = identity(
            displayName = Component.text("Alice", NamedTextColor.YELLOW),
            displayNamePlain = "Alice",
            usesLuckPermsPrefix = false,
            titleLegacy = "Mayor"
        )

        assertEquals("Mayor \u00A7eAlice", MayorNpcDisplayNames.legacy(identity, legacy))
    }

    @Test
    fun `mini npc name does not prepend title when LuckPerms prefix is already present`() {
        val identity = identity(
            displayName = Component.text("[Mayor]", NamedTextColor.GOLD)
                .append(Component.space())
                .append(Component.text("Alice", NamedTextColor.YELLOW)),
            displayNamePlain = "[Mayor] Alice",
            usesLuckPermsPrefix = true,
            titleMini = "<gold>Mayor</gold>"
        )

        assertEquals("<gold>[Mayor] <yellow>Alice", MayorNpcDisplayNames.mini(identity, mini))
    }

    @Test
    fun `mini fallback escapes display text before adding npc title`() {
        val identity = identity(
            displayName = Component.empty(),
            displayNamePlain = "<Admin> Alice",
            usesLuckPermsPrefix = false,
            titleMini = "<gold>Mayor</gold>"
        )

        assertEquals(
            "<gold>Mayor</gold> <yellow>&lt;Admin&gt; Alice</yellow>",
            MayorNpcDisplayNames.mini(identity, mini)
        )
    }

    private fun identity(
        displayName: Component,
        displayNamePlain: String,
        usesLuckPermsPrefix: Boolean,
        titleLegacy: String = "Mayor",
        titleMini: String = "<gold>Mayor</gold>"
    ): MayorNpcIdentity =
        MayorNpcIdentity(
            uuid = uuid,
            lastKnownName = "Alice",
            displayName = displayName,
            displayNamePlain = displayNamePlain,
            usesLuckPermsPrefix = usesLuckPermsPrefix,
            titleLegacy = titleLegacy,
            titleMini = titleMini
        )
}
