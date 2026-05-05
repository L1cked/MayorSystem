package mayorSystem.hologram

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import java.lang.reflect.Method

class DecentHologramsHook : LeaderboardHologramProvider {

    override val id: String = "decentholograms"
    override val pluginNames: Set<String> = setOf("DecentHolograms")

    private val mini = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacySection()
    private val miniTagRegex = Regex("</?[a-zA-Z0-9_:#-]+[^>]*>")

    private val apiClass: Class<*>? = runCatching {
        Class.forName("eu.decentsoftware.holograms.api.DHAPI")
    }.getOrNull()

    private val hologramClass: Class<*>? = runCatching {
        Class.forName("eu.decentsoftware.holograms.api.holograms.Hologram")
    }.getOrNull()

    private val createHologram: Method? = apiClass?.getMethod(
        "createHologram",
        String::class.java,
        Location::class.java,
        Boolean::class.javaPrimitiveType,
        List::class.java
    )

    private val getHologram: Method? = apiClass?.getMethod("getHologram", String::class.java)

    private val removeHologram: Method? = apiClass?.getMethod("removeHologram", String::class.java)

    private val moveHologram: Method? = apiClass?.getMethod("moveHologram", String::class.java, Location::class.java)

    private val setHologramLines: Method? = if (apiClass != null && hologramClass != null) {
        apiClass.getMethod("setHologramLines", hologramClass, List::class.java)
    } else {
        null
    }


    override fun isAvailable(plugin: mayorSystem.MayorPlugin): Boolean {
        val pluginEnabled = plugin.server.pluginManager.getPlugin("DecentHolograms")?.isEnabled == true
        return pluginEnabled && apiClass != null
    }

    override fun formatLines(lines: List<String>): List<String> =
        lines.map(::formatLine)

    override fun get(name: String): Any? {
        val m = getHologram ?: return null
        return runCatching { m.invoke(null, name) }.getOrNull()
    }

    override fun create(name: String, loc: Location, persistent: Boolean, lines: List<String>): Any? {
        val m = createHologram ?: return null
        return runCatching { m.invoke(null, name, loc, persistent, formatLines(lines)) }.getOrNull()
    }

    override fun move(hologram: Any, name: String, loc: Location) {
        val m = moveHologram ?: return
        runCatching { m.invoke(null, name, loc) }
    }

    override fun setLines(hologram: Any, lines: List<String>) {
        val m = setHologramLines ?: return
        runCatching { m.invoke(null, hologram, formatLines(lines)) }
    }

    override fun remove(name: String) {
        val m = removeHologram ?: return
        runCatching { m.invoke(null, name) }
    }

    private fun formatLine(raw: String): String {
        if (raw.isBlank()) return " "
        val trimmed = raw.trimEnd()
        val component = if (miniTagRegex.containsMatchIn(trimmed)) {
            runCatching { mini.deserialize(trimmed) }.getOrElse { Component.text(trimmed) }
        } else {
            Component.text(trimmed)
        }
        val legacyText = legacy.serialize(component)
        return if (legacyText.isBlank()) " " else legacyText
    }
}
