package mayorSystem.config

import mayorSystem.MayorPlugin
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Method

class Messages(private val plugin: MayorPlugin) {
    private val file = File(plugin.dataFolder, "messages.yml")
    private var yaml: YamlConfiguration = YamlConfiguration()
    private var defaults: YamlConfiguration = YamlConfiguration()
    private val mini = MiniMessage.miniMessage()
    private val papiSetPlaceholders: Method? = runCatching {
        val cls = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
        runCatching {
            cls.getMethod("setPlaceholders", Player::class.java, String::class.java)
        }.getOrElse {
            cls.getMethod("setPlaceholders", OfflinePlayer::class.java, String::class.java)
        }
    }.getOrNull()

    init {
        reload()
    }

    fun reload() {
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false)
        }
        yaml = YamlConfiguration.loadConfiguration(file)
        defaults = runCatching {
            plugin.getResource("messages.yml")?.use { stream ->
                YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
            }
        }.getOrNull() ?: YamlConfiguration()
    }

    fun msg(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        val raw = yaml.get(key) ?: defaults.get(key)
        when (raw) {
            is String -> sender.sendMessage(formatComponent(sender, applyPrefix(raw), placeholders))
            is List<*> -> raw.filterIsInstance<String>()
                .map { formatComponent(sender, applyPrefix(it), placeholders) }
                .forEach(sender::sendMessage)
            else -> sender.sendMessage(formatComponent(sender, applyPrefix(key), emptyMap()))
        }
    }

    fun get(key: String, placeholders: Map<String, String> = emptyMap()): String? {
        val raw = yaml.getString(key) ?: defaults.getString(key) ?: return null
        return formatRaw(null, raw, placeholders)
    }

    fun getList(key: String, placeholders: Map<String, String> = emptyMap()): List<String>? {
        val raw = yaml.get(key) ?: defaults.get(key) ?: return null
        return when (raw) {
            is List<*> -> raw.filterIsInstance<String>().map { formatRaw(null, it, placeholders) }
            is String -> listOf(formatRaw(null, raw, placeholders))
            else -> null
        }
    }

    fun contains(key: String): Boolean = yaml.contains(key)

    private fun formatComponent(sender: CommandSender, text: String, placeholders: Map<String, String>): Component {
        val out = formatRaw(sender, text, placeholders)
        return mini.deserialize(out)
    }

    private fun formatRaw(sender: CommandSender?, text: String, placeholders: Map<String, String>): String {
        var out = text
        placeholders.forEach { (k, v) ->
            out = out.replace("%$k%", v)
        }
        out = applyPapi(sender, out)
        return out
    }

    private fun applyPrefix(text: String): String {
        val prefix = yaml.getString("prefix")
            ?: defaults.getString("prefix")
            ?: "<gold><bold>Mayor</bold></gold> <dark_gray>>></dark_gray> "
        return prefix + text
    }

    private fun applyPapi(sender: CommandSender?, raw: String): String {
        val m = papiSetPlaceholders ?: return raw
        val player = sender as? Player ?: return raw
        return runCatching { m.invoke(null, player, raw) as? String }.getOrNull() ?: raw
    }
}

