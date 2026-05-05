package mayorSystem.config

import mayorSystem.MayorPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader

class GuiTexts(private val plugin: MayorPlugin) {
    private val file = File(plugin.dataFolder, "gui.yml")
    private var yaml: YamlConfiguration = YamlConfiguration()
    private var defaults: YamlConfiguration = YamlConfiguration()

    init {
        reload()
    }

    fun reload() {
        if (!file.exists()) {
            plugin.saveResource("gui.yml", false)
        }
        yaml = YamlConfiguration.loadConfiguration(file)
        defaults = runCatching {
            plugin.getResource("gui.yml")?.use { stream ->
                YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
            }
        }.getOrNull() ?: YamlConfiguration()
        if (ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults, plugin.logger)) {
            plugin.logger.info("Added missing default keys to gui.yml.")
            yaml = YamlConfiguration.loadConfiguration(file)
        }
        if (migrateLegacyDefaults()) {
            plugin.logger.info("Updated obsolete default gui values in gui.yml.")
            yaml = YamlConfiguration.loadConfiguration(file)
        }
    }

    fun get(key: String, placeholders: Map<String, String> = emptyMap()): String {
        val raw = yaml.getString(key) ?: defaults.getString(key) ?: key
        return format(raw, placeholders)
    }

    fun getList(key: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        val raw = yaml.get(key) ?: defaults.get(key) ?: return emptyList()
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

    private fun migrateLegacyDefaults(): Boolean {
        var changed = false

        changed = replaceLegacyDefault(
            path = "menus.mayor_profile.title",
            legacyValues = setOf(
                "<gradient:#f7971e:#ffd200>👑 %title_name%</gradient> <gray>• %name%</gray>"
            )
        ) || changed

        changed = replaceLegacyDefault(
            path = "menus.mayor_profile.head.name",
            legacyValues = setOf(
                "<gold>%title_name%</gold> <yellow>%name%</yellow>"
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
