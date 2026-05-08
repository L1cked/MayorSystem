package mayorSystem.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

internal object ConfigDefaultsSync {
    fun syncMissingKeys(file: File, yaml: YamlConfiguration, defaults: YamlConfiguration, log: Logger? = null): Boolean {
        if (defaults.getKeys(true).isEmpty()) return false

        val changed = copyMissingSectionKeys(defaults, yaml, "", log)
        if (changed) {
            try {
                yaml.save(file)
                log?.info("Config sync complete: saved updated config to ${file.name}")
            } catch (ex: IOException) {
                log?.log(Level.SEVERE, "Config sync failed to save updated config to ${file.absolutePath}.", ex)
                return false
            }
        }
        return changed
    }

    private fun copyMissingSectionKeys(
        source: ConfigurationSection,
        target: ConfigurationSection,
        pathPrefix: String = "",
        log: Logger? = null
    ): Boolean {
        var changed = false

        for (key in source.getKeys(false)) {
            val path = if (pathPrefix.isEmpty()) key else "$pathPrefix.$key"
            val child = source.getConfigurationSection(key)
            if (child != null) {
                if (!target.isConfigurationSection(path)) {
                    // If a previous version stored a scalar where we now need a section,
                    // replace it so nested defaults can be written.
                    log?.info("Config sync: creating missing section $path")
                    target.set(path, null)
                    target.createSection(path)
                    changed = true
                }
                changed = copyMissingSectionKeys(child, target, path, log) || changed
                continue
            }

            if (!target.isSet(path) || target.get(path) == null) {
                log?.fine("Config sync: adding missing key $path")
                target.set(path, source.get(key))
                changed = true
            }
        }

        return changed
    }
}
