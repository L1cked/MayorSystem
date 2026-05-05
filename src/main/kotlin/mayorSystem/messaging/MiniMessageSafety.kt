package mayorSystem.messaging

import net.kyori.adventure.text.minimessage.MiniMessage

object MiniMessageSafety {
    private val mini = MiniMessage.miniMessage()
    private val papiPlaceholderRegex = Regex("%[^%\\s]+%")

    fun applyPlaceholderApiSafely(raw: String, resolver: (String) -> String): String {
        var placeholderIndex = 0
        val marked = papiPlaceholderRegex.replace(raw) { match ->
            val open = openMarker(placeholderIndex)
            val close = closeMarker(placeholderIndex)
            placeholderIndex++
            open + match.value + close
        }
        if (placeholderIndex == 0) return raw

        var resolved = resolver(marked)
        for (index in 0 until placeholderIndex) {
            val open = openMarker(index)
            val close = closeMarker(index)
            val start = resolved.indexOf(open)
            if (start < 0) continue
            val end = resolved.indexOf(close, start + open.length)
            if (end < 0) continue

            val rawExpansion = resolved.substring(start + open.length, end)
            val sanitizedExpansion = sanitizeUntrustedMiniMessage(rawExpansion)
            resolved = buildString(resolved.length - open.length - close.length + sanitizedExpansion.length) {
                append(resolved, 0, start)
                append(sanitizedExpansion)
                append(resolved, end + close.length, resolved.length)
            }
        }
        return resolved
    }

    fun sanitizeUntrustedMiniMessage(raw: String): String =
        mini.escapeTags(mini.stripTags(raw))

    private fun openMarker(index: Int): String = "\u0007MSPAPI_${index}_OPEN\u0007"

    private fun closeMarker(index: Int): String = "\u0007MSPAPI_${index}_CLOSE\u0007"
}
