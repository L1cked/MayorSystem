package mayorSystem.npc

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

object MayorNpcDisplayNames {
    fun legacy(identity: MayorNpcIdentity, legacy: LegacyComponentSerializer): String {
        val title = identity.titleLegacy.trimEnd()
        val name = legacy.serialize(identity.displayName).trim().ifBlank { identity.displayNamePlain.trim() }
        return withNpcTitleWhenNeeded(title, name, identity.usesLuckPermsPrefix)
    }

    fun mini(identity: MayorNpcIdentity, mini: MiniMessage): String {
        val title = identity.titleMini.trimEnd()
        val name = mini.serialize(identity.displayName)
            .trim()
            .ifBlank { "<yellow>${escapeMiniMessage(identity.displayNamePlain)}</yellow>" }
        return withNpcTitleWhenNeeded(title, name, identity.usesLuckPermsPrefix)
    }

    private fun withNpcTitleWhenNeeded(title: String, name: String, hasExternalPrefix: Boolean): String {
        val normalizedName = name.trim()
        if (hasExternalPrefix || title.isBlank()) return normalizedName
        if (normalizedName.isBlank()) return title
        return "$title $normalizedName"
    }

    private fun escapeMiniMessage(s: String): String =
        s.replace("<", "&lt;").replace(">", "&gt;")
}
