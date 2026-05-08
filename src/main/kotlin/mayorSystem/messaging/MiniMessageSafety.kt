package mayorSystem.messaging

object MiniMessageSafety {
    private val papiPlaceholderRegex = Regex("%[^%\\s]+%")
    private val escapedTagRegex = Regex("\\\\<([^<>]+)>")
    private val colorTags = setOf(
        "black",
        "dark_blue",
        "dark_green",
        "dark_aqua",
        "dark_red",
        "dark_purple",
        "gold",
        "gray",
        "dark_gray",
        "blue",
        "green",
        "aqua",
        "red",
        "light_purple",
        "yellow",
        "white"
    )
    private val decorationTags = setOf(
        "bold",
        "b",
        "italic",
        "i",
        "underlined",
        "u",
        "strikethrough",
        "st",
        "obfuscated",
        "obf",
        "reset"
    )

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
        escapeUntrustedMiniMessage(raw)

    fun escapeUntrustedMiniMessage(raw: String): String =
        buildString(raw.length) {
            for (ch in raw) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '<' -> append("\\<")
                    else -> append(ch)
                }
            }
        }

    fun sanitizeTrustedFormattingMiniMessage(raw: String): String {
        val escaped = escapeUntrustedMiniMessage(raw)
        return escapedTagRegex.replace(escaped) { match ->
            val content = match.groupValues[1]
            if (isAllowedFormattingTag(content)) "<$content>" else match.value
        }
    }

    private fun isAllowedFormattingTag(content: String): Boolean {
        val body = content.removePrefix("/")
        val name = body.substringBefore(':').lowercase()
        val args = body.substringAfter(':', "")
        if (name in colorTags || name in decorationTags || isHexColor(name)) {
            return args.isBlank()
        }
        if (name == "gradient") {
            if (content.startsWith('/')) return args.isBlank()
            val colors = args.split(':').filter { it.isNotBlank() }
            return colors.size >= 2 && colors.all { it.lowercase() in colorTags || isHexColor(it) }
        }
        if (name == "rainbow") {
            return true
        }
        return false
    }

    private fun isHexColor(value: String): Boolean =
        value.length == 7 &&
            value[0] == '#' &&
            value.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun openMarker(index: Int): String = "\u0007MSPAPI_${index}_OPEN\u0007"

    private fun closeMarker(index: Int): String = "\u0007MSPAPI_${index}_CLOSE\u0007"
}
