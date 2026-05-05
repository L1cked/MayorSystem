package mayorSystem.hologram

import mayorSystem.MayorPlugin
import org.bukkit.Location

class DisabledLeaderboardHologramProvider : LeaderboardHologramProvider {

    override val id: String = "disabled"
    override val pluginNames: Set<String> = emptySet()

    override fun isAvailable(plugin: MayorPlugin): Boolean = false

    override fun formatLines(lines: List<String>): List<String> =
        lines.map { if (it.isBlank()) " " else it }

    override fun get(name: String): Any? = null

    override fun create(name: String, loc: Location, persistent: Boolean, lines: List<String>): Any? = null

    override fun move(hologram: Any, name: String, loc: Location) = Unit

    override fun setLines(hologram: Any, lines: List<String>) = Unit

    override fun remove(name: String) = Unit
}
