package mayorSystem.config

import mayorSystem.MayorPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class GuiTexts(private val plugin: MayorPlugin) {
    private val file = File(plugin.dataFolder, "gui.yml")
    private var yaml: YamlConfiguration = YamlConfiguration()

    init {
        reload()
    }

    fun reload() {
        if (!file.exists()) {
            plugin.saveResource("gui.yml", false)
        }
        yaml = YamlConfiguration.loadConfiguration(file)
    }

    fun get(key: String, placeholders: Map<String, String> = emptyMap()): String {
        val raw = yaml.getString(key) ?: key
        return format(raw, placeholders)
    }

    fun getList(key: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        val raw = yaml.getStringList(key)
        if (raw.isEmpty()) return emptyList()
        return raw.map { format(it, placeholders) }
    }

    private fun format(text: String, placeholders: Map<String, String>): String {
        var out = plugin.settings.applyTitleTokens(text)
        placeholders.forEach { (k, v) ->
            out = out.replace("%$k%", v)
        }
        return out
    }
}
