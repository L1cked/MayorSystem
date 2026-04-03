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
        if (ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults, plugin.logger)) {
            plugin.logger.info("Added missing default keys to messages.yml.")
            yaml = YamlConfiguration.loadConfiguration(file)
        }
        if (migrateLegacyDefaults()) {
            plugin.logger.info("Updated obsolete default messages in messages.yml.")
            yaml = YamlConfiguration.loadConfiguration(file)
        }
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
        var out = applyGlobalTokens(text)
        placeholders.forEach { (k, v) ->
            out = out.replace("%$k%", v)
        }
        out = applyPapi(sender, out)
        return out
    }

    private fun applyPrefix(text: String): String {
        val prefix = plugin.settings.chatPrefix.takeIf { it.isNotBlank() }
            ?: yaml.getString("prefix")
            ?: defaults.getString("prefix")
            ?: plugin.settings.chatPrefix
        return prefix + text
    }

    private fun applyGlobalTokens(text: String): String {
        var out = plugin.settings.applyTitleTokens(text)
        out = out.replace("%title_player_prefix%", plugin.settings.resolvedTitlePlayerPrefix())
        styleTokens().forEach { (k, v) ->
            out = out.replace("%$k%", v)
        }
        return out
    }

    private fun styleTokens(): Map<String, String> {
        val normal = styleValue("normal", "<gradient:#f7971e:#ffd200><bold>")
        val normalEnd = styleValue("normal_end", "</bold></gradient>")
        val warning = styleValue("warning", "<yellow>")
        val warningEnd = styleValue("warning_end", "</yellow>")
        val error = styleValue("error", "<red>")
        val errorEnd = styleValue("error_end", "</red>")
        val success = styleValue("success", "<green>")
        val successEnd = styleValue("success_end", "</green>")
        val blocked = styleValue("blocked", error)
        val blockedEnd = styleValue("blocked_end", errorEnd)
        val disabled = styleValue("disabled", error)
        val disabledEnd = styleValue("disabled_end", errorEnd)
        val info = styleValue("info", "<gray>")
        val infoEnd = styleValue("info_end", "</gray>")
        val accent = styleValue("accent", "<white>")
        val accentEnd = styleValue("accent_end", "</white>")
        return mapOf(
            "style_normal" to normal,
            "style_normal_end" to normalEnd,
            "style_warning" to warning,
            "style_warning_end" to warningEnd,
            "style_error" to error,
            "style_error_end" to errorEnd,
            "style_success" to success,
            "style_success_end" to successEnd,
            "style_blocked" to blocked,
            "style_blocked_end" to blockedEnd,
            "style_disabled" to disabled,
            "style_disabled_end" to disabledEnd,
            "style_info" to info,
            "style_info_end" to infoEnd,
            "style_accent" to accent,
            "style_accent_end" to accentEnd
        )
    }

    private fun styleValue(name: String, fallback: String): String {
        return yaml.getString("styles.$name")
            ?: defaults.getString("styles.$name")
            ?: fallback
    }

    private fun applyPapi(sender: CommandSender?, raw: String): String {
        val m = papiSetPlaceholders ?: return raw
        val player = sender as? Player ?: return raw
        return runCatching { m.invoke(null, player, raw) as? String }.getOrNull() ?: raw
    }

    private fun migrateLegacyDefaults(): Boolean {
        var changed = false

        changed = replaceLegacyDefault(
            path = "admin.hologram.not_available",
            legacyValues = setOf(
                "%style_error%DecentHolograms is not installed.%style_error_end%"
            )
        ) || changed

        if (changed) {
            yaml.save(file)
        }
        return changed
    }

    private fun replaceLegacyDefault(path: String, legacyValues: Set<String>): Boolean {
        val current = yaml.getString(path) ?: return false
        val replacement = defaults.getString(path) ?: return false
        if (current !in legacyValues) return false
        if (current == replacement) return false
        yaml.set(path, replacement)
        return true
    }
}

