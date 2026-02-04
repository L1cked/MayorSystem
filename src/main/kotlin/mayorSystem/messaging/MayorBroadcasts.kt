package mayorSystem.messaging

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.lang.reflect.Method

/**
 * Centralized chat-broadcast formatting used by MayorSystem.
 *
 * Goals:
 * - Never leak raw MiniMessage tags like <gold> into chat.
 * - If PlaceholderAPI is installed, apply placeholders per-player.
 * - Make broadcasts visually distinct (header/footer + padding).
 */
object MayorBroadcasts {

    private val mini = MiniMessage.miniMessage()
    private val legacyAmp = LegacyComponentSerializer.legacyAmpersand()
    private val legacySec = LegacyComponentSerializer.legacySection()

    // Very loose tag detector: if it looks like MiniMessage tags, parse as MiniMessage.
    private val miniTagRegex = Regex("</?[a-zA-Z0-9_:#\\-]+[^>]*>")

    // Optional dependency: PlaceholderAPI
    private val papiSetPlaceholders: Method? = runCatching {
        val cls = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
        // Prefer the most common signature:
        //   setPlaceholders(OfflinePlayer, String)
        runCatching {
            cls.getMethod("setPlaceholders", OfflinePlayer::class.java, String::class.java)
        }.getOrElse {
            // Older/alternate signature.
            cls.getMethod("setPlaceholders", Player::class.java, String::class.java)
        }
    }.getOrNull()

    fun hasPapi(): Boolean = papiSetPlaceholders != null

    private fun applyPapi(p: Player, raw: String): String {
        val m = papiSetPlaceholders ?: return raw
        return runCatching { m.invoke(null, p, raw) as? String }.getOrNull() ?: raw
    }

    /**
     * Deserialize either:
     * - MiniMessage (<gold> etc)
     * - Legacy (& / §)
     */
    fun deserialize(raw: String): Component {
        val trimmed = raw.trimEnd()
        return when {
            miniTagRegex.containsMatchIn(trimmed) -> runCatching { mini.deserialize(trimmed) }.getOrElse { Component.text(trimmed) }
            trimmed.contains('§') -> legacySec.deserialize(trimmed)
            else -> legacyAmp.deserialize(trimmed)
        }
    }

    /**
     * Broadcast a "Mayor"-styled chat message to all online players.
     *
     * Layout:
     *   Mayor Broadcast (bold + gold)
     *
     *   <message lines>
     *
     *   Use /mayor for more info! (bold + gold)
     */
    fun broadcastChat(messageLines: List<String>) {
        val header = Component.text("Mayor Broadcast", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
        val footer = Component.text("Use /mayor for more info!", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)

        Bukkit.getOnlinePlayers().forEach { p ->
            p.sendMessage(header)
            p.sendMessage(Component.text(" "))

            for (raw in messageLines) {
                if (raw.isBlank()) continue
                val withPapi = if (hasPapi()) applyPapi(p, raw) else raw
                p.sendMessage(deserialize(withPapi))
            }

            p.sendMessage(Component.text(" "))
            p.sendMessage(footer)
        }
    }

    /**
     * Same as [broadcastChat] but lets the caller generate per-player strings
     * (useful for built-in placeholders like %term% / %mayor_name%).
     */
    fun broadcastChat(messageLines: List<String>, perPlayerTransform: (Player, String) -> String) {
        val header = Component.text("Mayor Broadcast", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
        val footer = Component.text("Use /mayor for more info!", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)

        Bukkit.getOnlinePlayers().forEach { p ->
            p.sendMessage(header)
            p.sendMessage(Component.text(" "))

            for (raw in messageLines) {
                if (raw.isBlank()) continue
                val built = perPlayerTransform(p, raw)
                val withPapi = if (hasPapi()) applyPapi(p, built) else built
                p.sendMessage(deserialize(withPapi))
            }

            p.sendMessage(Component.text(" "))
            p.sendMessage(footer)
        }
    }
}

