package mayorSystem.cloud

import mayorSystem.MayorPlugin
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent

/**
 * Supports a dynamic command alias derived from title.name.
 *
 * We keep /mayor as the canonical root and rewrite:
 *   /<title_command> ... -> /mayor ...
 * where title_command is [a-z] lowercase only.
 */
class TitleCommandAliasListener(private val plugin: MayorPlugin) : Listener {
    private var warnedBlockedAlias: String? = null

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerCommand(e: PlayerCommandPreprocessEvent) {
        val raw = e.message.removePrefix("/")
        val rewritten = rewriteToCanonical(raw) ?: return
        e.message = "/$rewritten"
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onServerCommand(e: ServerCommandEvent) {
        val rewritten = rewriteToCanonical(e.command) ?: return
        e.command = rewritten
    }

    private fun rewriteToCanonical(raw: String): String? {
        val aliasEnabled = runCatching { plugin.settings.titleCommandAliasEnabled }.getOrDefault(true)
        if (!aliasEnabled) return null
        val alias = runCatching { plugin.settings.titleCommand }.getOrNull() ?: return null
        val trimmed = raw.trimStart()
        if (trimmed.isEmpty()) return null

        val firstSpace = trimmed.indexOf(' ')
        val label = if (firstSpace >= 0) trimmed.substring(0, firstSpace) else trimmed
        val rest = if (firstSpace >= 0) trimmed.substring(firstSpace + 1).trimStart() else ""

        if (alias == "mayor") return null
        if (isBlockedAlias(alias)) return null
        if (!label.equals(alias, ignoreCase = true)) return null

        return if (rest.isBlank()) "mayor" else "mayor $rest"
    }

    private fun isBlockedAlias(alias: String): Boolean {
        val lower = alias.lowercase()
        if (RESERVED_ALIASES.contains(lower)) {
            warnBlockedAlias(alias, "reserved server command label")
            return true
        }

        val existing = Bukkit.getPluginCommand(lower)
        if (existing != null && !existing.plugin.name.equals(plugin.name, ignoreCase = true)) {
            warnBlockedAlias(alias, "already registered by plugin ${existing.plugin.name}")
            return true
        }
        return false
    }

    private fun warnBlockedAlias(alias: String, reason: String) {
        if (warnedBlockedAlias.equals(alias, ignoreCase = true)) return
        warnedBlockedAlias = alias
        plugin.logger.warning(
            "Dynamic title command alias '$alias' is disabled: $reason. " +
                "Change title.name or set title.command_alias_enabled=false."
        )
    }

    private companion object {
        val RESERVED_ALIASES: Set<String> = setOf(
            "stop",
            "reload",
            "pl",
            "plugins",
            "help",
            "op",
            "deop",
            "ban",
            "pardon",
            "kick",
            "whitelist",
            "execute",
            "sudo",
            "lp",
            "luckperms",
            "pex"
        )
    }
}
