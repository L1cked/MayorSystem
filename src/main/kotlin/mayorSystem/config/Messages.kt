package mayorSystem.config

import mayorSystem.MayorPlugin
import mayorSystem.messaging.MiniMessageSafety
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.logging.Level

class Messages(private val plugin: MayorPlugin) {
    private val file = File(plugin.dataFolder, "messages.yml")
    @Volatile private var holder: ConfigHolder = ConfigHolder(YamlConfiguration(), YamlConfiguration())
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
        var yaml = YamlConfiguration.loadConfiguration(file)
        val defaults = runCatching {
            plugin.getResource("messages.yml")?.use { stream ->
                YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
            }
        }.getOrNull() ?: YamlConfiguration()
        if (ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults, plugin.logger)) {
            plugin.logger.info("Added missing default keys to messages.yml.")
            yaml = YamlConfiguration.loadConfiguration(file)
        }
        if (migrateLegacyDefaults(yaml, defaults)) {
            plugin.logger.info("Updated obsolete default messages in messages.yml.")
            yaml = YamlConfiguration.loadConfiguration(file)
        }
        holder = ConfigHolder(yaml, defaults)
    }

    fun msg(
        sender: CommandSender,
        key: String,
        placeholders: Map<String, String> = emptyMap(),
        trustedMiniMessagePlaceholders: Set<String> = emptySet()
    ) {
        val current = holder
        val raw = current.yaml.get(key) ?: current.defaults.get(key)
        when (raw) {
            is String -> sender.sendMessage(formatComponent(sender, applyPrefix(raw, current), placeholders, trustedMiniMessagePlaceholders, current))
            is List<*> -> raw.filterIsInstance<String>()
                .map { formatComponent(sender, applyPrefix(it, current), placeholders, trustedMiniMessagePlaceholders, current) }
                .forEach(sender::sendMessage)
            else -> sender.sendMessage(formatComponent(sender, applyPrefix(key, current), emptyMap(), holder = current))
        }
    }

    fun get(key: String, placeholders: Map<String, String> = emptyMap()): String? {
        val current = holder
        val raw = current.yaml.getString(key) ?: current.defaults.getString(key) ?: return null
        return formatRaw(null, raw, placeholders, holder = current)
    }

    fun getList(key: String, placeholders: Map<String, String> = emptyMap()): List<String>? {
        val current = holder
        val raw = current.yaml.get(key) ?: current.defaults.get(key) ?: return null
        return when (raw) {
            is List<*> -> raw.filterIsInstance<String>().map { formatRaw(null, it, placeholders, holder = current) }
            is String -> listOf(formatRaw(null, raw, placeholders, holder = current))
            else -> null
        }
    }

    fun contains(key: String): Boolean = holder.yaml.contains(key)

    private fun formatComponent(
        sender: CommandSender,
        text: String,
        placeholders: Map<String, String>,
        trustedMiniMessagePlaceholders: Set<String> = emptySet(),
        holder: ConfigHolder = this.holder
    ): Component {
        val out = formatRaw(sender, text, placeholders, trustedMiniMessagePlaceholders, holder)
        return mini.deserialize(out)
    }

    private fun formatRaw(
        sender: CommandSender?,
        text: String,
        placeholders: Map<String, String>,
        trustedMiniMessagePlaceholders: Set<String> = emptySet(),
        holder: ConfigHolder = this.holder
    ): String {
        var out = applyGlobalTokens(text, holder)
        placeholders.forEach { (k, v) ->
            val replacement = if (k in trustedMiniMessagePlaceholders) {
                MiniMessageSafety.sanitizeTrustedFormattingMiniMessage(v)
            } else {
                MiniMessageSafety.escapeUntrustedMiniMessage(v)
            }
            out = out.replace("%$k%", replacement)
        }
        out = applyPapi(sender, out)
        return out
    }

    private fun applyPrefix(text: String, holder: ConfigHolder): String {
        val prefix = plugin.settings.chatPrefix.takeIf { it.isNotBlank() }
            ?: holder.yaml.getString("prefix")
            ?: holder.defaults.getString("prefix")
            ?: plugin.settings.chatPrefix
        return prefix + text
    }

    private fun applyGlobalTokens(text: String, holder: ConfigHolder): String {
        var out = plugin.settings.applyTitleTokens(text)
        out = out.replace("%title_player_prefix%", plugin.settings.resolvedTitlePlayerPrefix())
        styleTokens(holder).forEach { (k, v) ->
            out = out.replace("%$k%", v)
        }
        return out
    }

    private fun styleTokens(holder: ConfigHolder): Map<String, String> {
        val normal = styleValue(holder, "normal", "<gradient:#f7971e:#ffd200><bold>")
        val normalEnd = styleValue(holder, "normal_end", "</bold></gradient>")
        val warning = styleValue(holder, "warning", "<yellow>")
        val warningEnd = styleValue(holder, "warning_end", "</yellow>")
        val error = styleValue(holder, "error", "<red>")
        val errorEnd = styleValue(holder, "error_end", "</red>")
        val success = styleValue(holder, "success", "<green>")
        val successEnd = styleValue(holder, "success_end", "</green>")
        val blocked = styleValue(holder, "blocked", error)
        val blockedEnd = styleValue(holder, "blocked_end", errorEnd)
        val disabled = styleValue(holder, "disabled", error)
        val disabledEnd = styleValue(holder, "disabled_end", errorEnd)
        val info = styleValue(holder, "info", "<gray>")
        val infoEnd = styleValue(holder, "info_end", "</gray>")
        val accent = styleValue(holder, "accent", "<white>")
        val accentEnd = styleValue(holder, "accent_end", "</white>")
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

    private fun styleValue(holder: ConfigHolder, name: String, fallback: String): String {
        return holder.yaml.getString("styles.$name")
            ?: holder.defaults.getString("styles.$name")
            ?: fallback
    }

    private fun applyPapi(sender: CommandSender?, raw: String): String {
        val m = papiSetPlaceholders ?: return raw
        val player = sender as? Player ?: return raw
        return MiniMessageSafety.applyPlaceholderApiSafely(raw) { input ->
            runCatching { m.invoke(null, player, input) as? String }.getOrNull() ?: input
        }
    }

    private fun migrateLegacyDefaults(yaml: YamlConfiguration, defaults: YamlConfiguration): Boolean {
        var changed = false

        changed = replaceLegacyDefault(
            yaml,
            defaults,
            path = "admin.hologram.not_available",
            legacyValues = setOf(
                "%style_error%DecentHolograms is not installed.%style_error_end%"
            )
        ) || changed

        if (changed) {
            try {
                yaml.save(file)
            } catch (ex: IOException) {
                plugin.logger.log(Level.SEVERE, "Failed to save migrated message defaults to ${file.absolutePath}.", ex)
                return false
            }
        }
        return changed
    }

    private fun replaceLegacyDefault(
        yaml: YamlConfiguration,
        defaults: YamlConfiguration,
        path: String,
        legacyValues: Set<String>
    ): Boolean {
        val current = yaml.getString(path) ?: return false
        val replacement = defaults.getString(path) ?: return false
        if (current !in legacyValues) return false
        if (current == replacement) return false
        yaml.set(path, replacement)
        return true
    }

    private data class ConfigHolder(
        val yaml: YamlConfiguration,
        val defaults: YamlConfiguration
    )
}

