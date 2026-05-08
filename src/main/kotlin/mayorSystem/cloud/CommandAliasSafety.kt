package mayorSystem.cloud

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

object CommandAliasSafety {
    private val aliasRegex = Regex("^[a-z]+$")

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

    /**
     * Must be called from the Bukkit primary thread because command/help maps are Bukkit state.
     */
    fun blockedReason(plugin: Plugin, alias: String): String? {
        val lower = alias.trim().lowercase()
        if (lower.isBlank() || !aliasRegex.matches(lower)) {
            return "invalid command alias format"
        }
        if (RESERVED_ALIASES.contains(lower)) {
            return "reserved server command label"
        }

        check(Bukkit.isPrimaryThread()) { "CommandAliasSafety.blockedReason must run on the Bukkit primary thread." }

        val existing = Bukkit.getPluginCommand(lower)
        if (existing != null) {
            return if (existing.plugin.name.equals(plugin.name, ignoreCase = true)) {
                null
            } else {
                "already registered by plugin ${existing.plugin.name}"
            }
        }

        val helpTopic = runCatching { Bukkit.getHelpMap().getHelpTopic("/$lower") }.getOrNull()
        if (helpTopic != null) {
            return "already registered by the server command map"
        }

        return null
    }
}
