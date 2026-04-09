package mayorSystem.cloud

import mayorSystem.MayorPlugin
import mayorSystem.config.SystemGateOption
import mayorSystem.security.Perms
import mayorSystem.service.ActionResult
import mayorSystem.ui.Menu
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.incendo.cloud.Command
import org.incendo.cloud.paper.LegacyPaperCommandManager
import org.incendo.cloud.permission.Permission
import java.time.Duration
import java.util.UUID

class CommandContext(
    val plugin: MayorPlugin,
    val cm: LegacyPaperCommandManager<CommandSender>
) {
    private val cooldowns = mutableMapOf<String, MutableMap<UUID, Long>>()

    init {
        plugin.server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onQuit(e: PlayerQuitEvent) {
                val id = e.player.uniqueId
                for (bucket in cooldowns.values) {
                    bucket.remove(id)
                }
            }
        }, plugin)
    }

    fun requireReady(sender: CommandSender): Boolean {
        if (plugin.isReady()) return true
        msg(sender, "public.loading")
        return false
    }

    fun msg(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        plugin.messages.msg(sender, key, placeholders)
    }

    fun dispatch(sender: CommandSender, result: ActionResult) {
        result.send(plugin, sender)
    }

    fun withPlayer(sender: CommandSender, block: (Player) -> Unit) {
        val player = sender as? Player
        if (player == null) {
            msg(sender, "errors.player_only")
            return
        }
        if (!requireReady(player)) return
        block(player)
    }

    fun registerMenuRoute(
        literals: List<String>,
        permission: Permission,
        menuFactory: (Player) -> Menu,
        requirePublicAccess: Boolean = false,
        cooldownKey: String? = null,
        cooldown: Duration? = null
    ) {
        var builder = rootCommandBuilder()
        for (literal in literals) {
            builder = builder.literal(literal)
        }
        cm.command(
            builder
                .permission(permission)
                .handler { ctx ->
                    val sender = ctx.sender()
                    val player = sender as? Player
                    if (player == null) {
                        msg(sender, "errors.player_only")
                        return@handler
                    }
                    if (!requireReady(player)) return@handler
                    if (requirePublicAccess && !checkPublicAccess(player)) return@handler
                    if (cooldownKey != null && cooldown != null && checkCooldown(player, cooldownKey, cooldown)) {
                        return@handler
                    }
                    plugin.gui.open(player, menuFactory(player))
                }
        )
    }

    fun checkPublicAccess(player: Player): Boolean {
        if (!requireReady(player)) return false
        if (plugin.settings.isDisabled(SystemGateOption.ACTIONS) && !Perms.isAdmin(player)) {
            msg(player, "public.disabled")
            return false
        }
        if (!plugin.settings.publicEnabled && !Perms.isAdmin(player)) {
            msg(player, "public.closed")
            return false
        }
        return true
    }

    fun blockIfActionsPaused(player: Player): Boolean {
        if (!requireReady(player)) return true
        if (plugin.settings.isDisabled(SystemGateOption.ACTIONS)) {
            msg(player, "public.disabled")
            return true
        }
        if (plugin.settings.isPaused(SystemGateOption.ACTIONS)) {
            msg(player, "public.paused")
            return true
        }
        return false
    }

    fun parseBool(input: String): Boolean? {
        return when (input.lowercase()) {
            "true", "on", "yes", "1" -> true
            "false", "off", "no", "0" -> false
            else -> null
        }
    }

    fun resolveToggle(input: String, current: Boolean): Boolean? {
        return when (input.lowercase()) {
            "toggle", "t" -> !current
            "on", "enable", "enabled", "true", "yes" -> true
            "off", "disable", "disabled", "false", "no" -> false
            else -> null
        }
    }

    fun findOnlinePlayer(name: String): Player? {
        return Bukkit.getPlayerExact(name)
            ?: Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    fun sendOnlinePlayers(to: Player) {
        val names = Bukkit.getOnlinePlayers().map { it.name }.sortedBy { it.lowercase() }
        if (names.isEmpty()) {
            msg(to, "admin.online_players.none")
            return
        }

        val shown = names.take(25)
        val suffix = if (names.size > shown.size) " ... (+${names.size - shown.size})" else ""
        msg(to, "admin.online_players.list", mapOf("names" to shown.joinToString(", "), "suffix" to suffix))
    }

    fun optionListString(): String =
        SystemGateOption.values().joinToString(", ") { it.name }

    fun currentGateOptions(path: String): MutableSet<SystemGateOption> {
        val hasKey = plugin.config.contains(path)
        val raw = plugin.config.getStringList(path)
        if (raw.isEmpty()) {
            return if (hasKey) mutableSetOf() else SystemGateOption.all().toMutableSet()
        }
        val out = mutableSetOf<SystemGateOption>()
        for (entry in raw) {
            val opt = SystemGateOption.parse(entry) ?: continue
            out += opt
        }
        return out
    }

    fun checkCooldown(player: Player, key: String, duration: Duration): Boolean {
        val now = System.currentTimeMillis()
        val bucket = cooldowns.getOrPut(key) { mutableMapOf() }
        val last = bucket[player.uniqueId]
        if (last != null) {
            val remaining = duration.toMillis() - (now - last)
            if (remaining > 0) {
                val seconds = (remaining / 1000.0).coerceAtLeast(0.1)
                msg(player, "cooldown.wait", mapOf("seconds" to String.format(java.util.Locale.US, "%.1f", seconds)))
                return true
            }
        }
        bucket[player.uniqueId] = now
        return false
    }

    fun rootCommandBuilder(): Command.Builder<CommandSender> {
        val alias = dynamicRootAlias()
        return if (alias == null) {
            cm.commandBuilder("mayor")
        } else {
            cm.commandBuilder("mayor", alias)
        }
    }

    private fun dynamicRootAlias(): String? {
        if (!plugin.settings.titleCommandAliasEnabled) return null
        val alias = plugin.settings.titleCommand.lowercase().trim()
        if (alias.isBlank() || alias == "mayor") return null
        if (RESERVED_ALIASES.contains(alias)) return null

        val existing = Bukkit.getPluginCommand(alias)
        if (existing != null && !existing.plugin.name.equals(plugin.name, ignoreCase = true)) {
            return null
        }
        return alias
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

