package mayorSystem.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.logging.Logger

internal object ConfigDefaultsSync {
    private var logger: Logger? = null

    fun syncMissingKeys(file: File, yaml: YamlConfiguration, defaults: YamlConfiguration, log: Logger? = null): Boolean {
        logger = log
        if (defaults.getKeys(true).isEmpty()) return false

        val changed = copyMissingSectionKeys(defaults, yaml, "")
        if (changed) {
            yaml.save(file)
            logger?.info("Config sync complete: saved updated config to ${file.name}")
        }
        return changed
    }

    private fun copyMissingSectionKeys(
        source: ConfigurationSection,
        target: ConfigurationSection,
        pathPrefix: String = ""
    ): Boolean {
        var changed = false

        for (key in source.getKeys(false)) {
            val path = if (pathPrefix.isEmpty()) key else "$pathPrefix.$key"
            val child = source.getConfigurationSection(key)
            if (child != null) {
                if (!target.isConfigurationSection(path)) {
                    // If a previous version stored a scalar where we now need a section,
                    // replace it so nested defaults can be written.
                    logger?.info("Config sync: creating missing section $path")
                    target.set(path, null)
                    target.createSection(path)
                    changed = true
                }
                changed = copyMissingSectionKeys(child, target, path) || changed
                continue
            }

            if (!target.isSet(path) || target.get(path) == null) {
                logger?.fine("Config sync: adding missing key $path")
                target.set(path, source.get(key))
                changed = true
            }
        }

        return changed
    }
}
