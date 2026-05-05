package mayorSystem.messaging

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure display-text parser for trusted MayorSystem display config and
 * integration-provided legacy text. It does not touch Bukkit or plugin state.
 */
object DisplayTextParser {
    private const val MAX_CACHE_SIZE = 512

    private val mini = MiniMessage.miniMessage()
    private val legacyAmpersand = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()
    private val legacySection = LegacyComponentSerializer.builder()
        .character('\u00A7')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()
    private val plainSerializer = PlainTextComponentSerializer.plainText()

    private val ampersandHexPattern = Regex("(?i)&\\#([0-9a-f]{6})")
    private val sectionHexPattern = Regex("(?i)\u00A7\\#([0-9a-f]{6})")
    private val ampersandLegacyPattern = Regex("(?i)&(?:#[0-9a-f]{6}|[0-9a-fk-orx])")
    private val sectionLegacyPattern = Regex("(?i)\u00A7(?:#[0-9a-f]{6}|[0-9a-fk-orx])")
    private val miniLikeTagPattern = Regex("</?[a-zA-Z0-9_:#-]+(?:[: ][^>]*)?>")
    private val componentCache = ConcurrentHashMap<CacheKey, Component>()

    fun component(raw: String, parseMiniMessage: Boolean = true): Component {
        val text = raw.trim()
        if (text.isBlank()) return Component.empty()
        val key = CacheKey(text, parseMiniMessage)
        componentCache[key]?.let { return it }

        val parsed = parseComponent(text, parseMiniMessage) ?: Component.text(escapePlain(text))
        if (componentCache.size >= MAX_CACHE_SIZE) componentCache.clear()
        componentCache[key] = parsed
        return parsed
    }

    fun mini(raw: String, parseMiniMessage: Boolean = true): String =
        mini(component(raw, parseMiniMessage))

    fun mini(component: Component): String =
        mini.serialize(component)

    fun plain(raw: String, parseMiniMessage: Boolean = true): String =
        plain(component(raw, parseMiniMessage))

    fun plain(component: Component): String =
        plainSerializer.serialize(component).trim()

    fun legacyAmpersand(raw: String, parseMiniMessage: Boolean = true): String =
        legacyAmpersand.serialize(component(raw, parseMiniMessage))

    fun escapePlain(raw: String): String =
        raw.replace("<", "").replace(">", "")

    private fun parseComponent(text: String, parseMiniMessage: Boolean): Component? {
        if (parseMiniMessage && miniLikeTagPattern.containsMatchIn(text)) {
            deserializeMiniSafely(text)?.let { return it }
        }

        return when {
            sectionLegacyPattern.containsMatchIn(text) -> {
                runCatching { legacySection.deserialize(normalizeSectionHex(text)) }.getOrNull()
            }
            ampersandLegacyPattern.containsMatchIn(text) -> {
                runCatching { legacyAmpersand.deserialize(normalizeAmpersandHex(text)) }.getOrNull()
            }
            parseMiniMessage -> {
                deserializeMiniSafely(text)
            }
            else -> null
        }
    }

    private fun deserializeMiniSafely(text: String): Component? {
        val component = runCatching { mini.deserialize(text) }.getOrNull() ?: return null
        val renderedPlain = plainSerializer.serialize(component)
        return component.takeUnless { miniLikeTagPattern.containsMatchIn(renderedPlain) }
    }

    private fun normalizeAmpersandHex(text: String): String =
        ampersandHexPattern.replace(text) { match ->
            val hex = match.groupValues[1].lowercase()
            buildString(14) {
                append("&x")
                for (char in hex) {
                    append('&')
                    append(char)
                }
            }
        }

    private fun normalizeSectionHex(text: String): String =
        sectionHexPattern.replace(text) { match ->
            val hex = match.groupValues[1].lowercase()
            buildString(14) {
                append('\u00A7')
                append('x')
                for (char in hex) {
                    append('\u00A7')
                    append(char)
                }
            }
        }

    private data class CacheKey(
        val text: String,
        val parseMiniMessage: Boolean
    )
}
