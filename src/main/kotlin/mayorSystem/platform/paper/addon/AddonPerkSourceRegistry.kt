package mayorSystem.platform.paper.addon

import mayorSystem.api.MayorAddonRegistration
import mayorSystem.api.MayorPerkDefinition
import mayorSystem.api.MayorPerkSection
import mayorSystem.api.MayorPerkSource
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

class AddonPerkSourceRegistry(
    private val config: () -> FileConfiguration,
    private val logger: Logger,
    private val saveConfig: () -> Unit,
    private val reloadPerks: () -> Unit
) {
    private data class Entry(
        val owner: Plugin,
        val source: MayorPerkSource
    )

    private val entries = linkedMapOf<String, Entry>()

    fun register(owner: Plugin, source: MayorPerkSource): MayorAddonRegistration {
        require(owner.isEnabled) { "Owner plugin '${owner.name}' must be enabled." }
        val sourceId = normalizeId(source.id, "source id")
        synchronized(this) {
            require(!entries.containsKey(sourceId)) { "Mayor perk source '$sourceId' is already registered." }
            validateSources(
                entries.values
                    .filter { it.owner.isEnabled && it.source.available }
                    .map { it.source } + source
            )
            entries[sourceId] = Entry(owner, source)
        }
        try {
            seedRegisteredSources(reloadOnChange = true)
        } catch (ex: RuntimeException) {
            synchronized(this) {
                entries.remove(sourceId)
            }
            throw ex
        }
        return Registration(sourceId)
    }

    fun unregisterOwner(owner: Plugin) {
        val changed = synchronized(this) {
            val owned = entries.filterValues { it.owner == owner }.keys.toList()
            owned.forEach { entries.remove(it) }
            owned.isNotEmpty()
        }
        if (changed) {
            seedRegisteredSources(reloadOnChange = true)
        }
    }

    fun seedRegisteredSources(reloadOnChange: Boolean = false): Boolean {
        val active = synchronized(this) {
            entries.values
                .filter { it.owner.isEnabled && it.source.available }
                .map { it.source }
                .toList()
        }
        val changed = seedSources(active)
        if (changed) {
            saveConfig()
            if (reloadOnChange) {
                reloadPerks()
            }
        }
        return changed
    }

    fun seedSources(sources: Collection<MayorPerkSource>): Boolean {
        validateSources(sources)
        var changed = false
        for (source in sources) {
            val sourceId = normalizeId(source.id, "source id")
            if (!source.available) continue
            val sections = runCatching { source.sections() }.getOrElse {
                logger.warning("Mayor perk source '$sourceId' failed to provide sections: ${it.message}")
                emptyList()
            }
            for (section in sections) {
                val sectionId = normalizeId(section.id, "section id")
                changed = seedSection(section.copy(id = sectionId)) || changed
            }
        }
        return changed
    }

    private fun validateSources(sources: Collection<MayorPerkSource>) {
        val seenSections = linkedMapOf<String, String>()
        for (source in sources) {
            val sourceId = normalizeId(source.id, "source id")
            if (!source.available) continue
            val sections = runCatching { source.sections() }.getOrElse {
                throw IllegalArgumentException("Mayor perk source '$sourceId' failed to provide sections.", it)
            }
            for (section in sections) {
                val sectionId = normalizeId(section.id, "section id")
                val existingSource = seenSections.putIfAbsent(sectionId, sourceId)
                require(existingSource == null || existingSource == sourceId) {
                    "Mayor perk section '$sectionId' is provided by both '$existingSource' and '$sourceId'."
                }
                section.perks.forEach { perk ->
                    normalizeId(perk.id, "perk id")
                }
            }
        }
    }

    private fun seedSection(section: MayorPerkSection): Boolean {
        val cfg = config()
        val base = "perks.sections.${section.id}"
        var changed = false
        if (cfg.getConfigurationSection(base) == null) {
            cfg.set("$base.enabled", section.enabled)
            cfg.set("$base.pick_limit", section.pickLimit.coerceAtLeast(0))
            cfg.set("$base.display_name", section.displayName)
            cfg.set("$base.icon", section.icon)
            cfg.set("$base.perks", linkedMapOf<String, Any>())
            changed = true
        }

        for (perk in section.perks) {
            val perkId = normalizeId(perk.id, "perk id")
            val dest = "$base.perks.$perkId"
            changed = seedPerk(dest, perk.copy(id = perkId)) || changed
        }
        return changed
    }

    private fun seedPerk(path: String, perk: MayorPerkDefinition): Boolean {
        val cfg = config()
        if (!cfg.contains(path)) {
            cfg.set("$path.enabled", perk.enabled)
            cfg.set("$path.display_name", perk.displayName)
            cfg.set("$path.icon", perk.icon)
            cfg.set("$path.lore", perk.lore)
            cfg.set("$path.admin_lore", perk.adminLore)
            cfg.set("$path.on_start", perk.onStart)
            cfg.set("$path.on_end", perk.onEnd)
            return true
        }

        var changed = false
        changed = syncIfMissing("$path.display_name", perk.displayName) || changed
        changed = syncIfMissing("$path.icon", perk.icon) || changed
        changed = syncIfMissing("$path.lore", perk.lore) || changed
        changed = syncIfMissing("$path.admin_lore", perk.adminLore) || changed
        changed = syncIfMissing("$path.on_start", perk.onStart) || changed
        changed = syncIfMissing("$path.on_end", perk.onEnd) || changed
        return changed
    }

    private fun syncIfMissing(path: String, value: Any?): Boolean {
        val cfg = config()
        if (cfg.contains(path)) return false
        cfg.set(path, value)
        return true
    }

    private fun normalizeId(raw: String, label: String): String {
        val id = raw.trim().lowercase()
        require(ID_REGEX.matches(id)) { "Invalid Mayor perk $label '$raw'." }
        return id
    }

    private inner class Registration(private val sourceId: String) : MayorAddonRegistration {
        @Volatile
        private var closed = false

        override fun close() {
            if (closed) return
            val removed = synchronized(this@AddonPerkSourceRegistry) {
                if (closed) {
                    false
                } else {
                    closed = true
                    entries.remove(sourceId) != null
                }
            }
            if (removed) {
                seedRegisteredSources(reloadOnChange = true)
            }
        }
    }

    companion object {
        val ID_REGEX = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
    }
}
