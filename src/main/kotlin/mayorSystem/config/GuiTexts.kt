package mayorSystem.config

import mayorSystem.MayorPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.logging.Level

class GuiTexts(private val plugin: MayorPlugin) {
    private val file = File(plugin.dataFolder, "gui.yml")
    @Volatile private var holder: ConfigHolder = ConfigHolder(YamlConfiguration(), YamlConfiguration())

    init {
        reload()
    }

    fun reload() {
        if (!file.exists()) {
            plugin.saveResource("gui.yml", false)
        }
        var yaml = YamlConfiguration.loadConfiguration(file)
        val defaults = runCatching {
            plugin.getResource("gui.yml")?.use { stream ->
                YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
            }
        }.getOrNull() ?: YamlConfiguration()
        if (ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults, plugin.logger)) {
            plugin.logger.info("Added missing default keys to gui.yml.")
            yaml = YamlConfiguration.loadConfiguration(file)
        }
        if (migrateLegacyDefaults(yaml, defaults)) {
            plugin.logger.info("Updated obsolete default gui values in gui.yml.")
            yaml = YamlConfiguration.loadConfiguration(file)
        }
        holder = ConfigHolder(yaml, defaults)
    }

    fun get(key: String, placeholders: Map<String, String> = emptyMap()): String {
        val current = holder
        val raw = current.yaml.getString(key) ?: current.defaults.getString(key) ?: key
        return format(raw, placeholders)
    }

    fun getList(key: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        val current = holder
        val raw = current.yaml.get(key) ?: current.defaults.get(key) ?: return emptyList()
        return when (raw) {
            is List<*> -> raw.filterIsInstance<String>().map { format(it, placeholders) }
            is String -> listOf(format(raw, placeholders))
            else -> emptyList()
        }
    }

    private fun format(text: String, placeholders: Map<String, String>): String {
        var out = plugin.settings.applyTitleTokens(text)
        placeholders.forEach { (k, v) ->
            out = out.replace("%$k%", v)
        }
        return out
    }

    private fun migrateLegacyDefaults(yaml: YamlConfiguration, defaults: YamlConfiguration): Boolean {
        var changed = false

        changed = replaceLegacyDefault(
            yaml,
            defaults,
            path = "menus.mayor_profile.title",
            legacyValues = setOf(
                "<gradient:#f7971e:#ffd200>👑 %title_name%</gradient> <gray>• %name%</gray>"
            )
        ) || changed

        changed = replaceLegacyDefault(
            yaml,
            defaults,
            path = "menus.mayor_profile.head.name",
            legacyValues = setOf(
                "<gold>%title_name%</gold> <yellow>%name%</yellow>"
            )
        ) || changed

        if (changed) {
            try {
                yaml.save(file)
            } catch (ex: IOException) {
                plugin.logger.log(Level.SEVERE, "Failed to save migrated gui defaults to ${file.absolutePath}.", ex)
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
