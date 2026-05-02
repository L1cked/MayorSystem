package mayorSystem.rewards

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

object DisplayRewardText {
    private val mini = MiniMessage.miniMessage()
    private val legacyAmpersand = LegacyComponentSerializer.legacyAmpersand()
    private val legacySection = LegacyComponentSerializer.legacySection()
    private val plainSerializer = PlainTextComponentSerializer.plainText()
    private val legacyColorPattern = Regex("(?i)&([0-9A-FK-ORX])")

    fun previewMini(raw: String): String =
        mini.serialize(component(raw))

    fun plain(raw: String): String =
        plainSerializer.serialize(component(raw)).ifBlank { raw.replace("<", "").replace(">", "") }

    fun escapeMini(raw: String): String =
        raw.replace("<", "").replace(">", "")

    private fun component(raw: String): Component {
        val text = raw.trim()
        if (text.isBlank()) return Component.empty()
        return when {
            text.contains('\u00A7') -> runCatching { legacySection.deserialize(text) }.getOrNull()
            legacyColorPattern.containsMatchIn(text) -> runCatching { legacyAmpersand.deserialize(text) }.getOrNull()
            else -> runCatching { mini.deserialize(text) }.getOrNull()
        } ?: Component.text(escapeMini(text))
    }
}
