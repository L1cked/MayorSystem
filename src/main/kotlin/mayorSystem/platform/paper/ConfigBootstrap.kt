package mayorSystem.platform.paper

import mayorSystem.MayorPlugin
import mayorSystem.config.ConfigDefaultsSync
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader

class ConfigBootstrap(private val plugin: MayorPlugin) {

    fun syncConfigDefaults(): Boolean {
        val file = File(plugin.dataFolder, "config.yml")
        val yaml = YamlConfiguration.loadConfiguration(file)
        val hadDisplayReward = yaml.isConfigurationSection("display_reward")
        val legacyGroupEnabled = yaml.getBoolean("title.username_group_enabled", true)
        val legacyGroup = yaml.getString("title.username_group")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "mayor_current"
        val defaults = runCatching {
            plugin.getResource("config.yml")?.use { stream ->
                YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
            }
        }.getOrElse { ex ->
            plugin.logger.warning(
                "Could not load bundled config.yml defaults; config default sync will run with an empty default set: " +
                    (ex.message ?: ex::class.java.simpleName)
            )
            YamlConfiguration()
        } ?: run {
            plugin.logger.warning("Bundled config.yml defaults were not found; config default sync will run with an empty default set.")
            YamlConfiguration()
        }

        val changed = ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults, plugin.logger)
        if (changed && !hadDisplayReward && yaml.isConfigurationSection("display_reward")) {
            yaml.set("display_reward.enabled", legacyGroupEnabled)
            yaml.set("display_reward.default_mode", "RANK")
            yaml.set("display_reward.rank.enabled", legacyGroupEnabled)
            yaml.set("display_reward.rank.luckperms_group", legacyGroup)
            yaml.save(file)
        }
        if (changed) {
            plugin.reloadConfig()
        }
        return changed
    }
}
