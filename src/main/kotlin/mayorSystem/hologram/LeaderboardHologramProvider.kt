package mayorSystem.hologram

import mayorSystem.MayorPlugin
import org.bukkit.Location

interface LeaderboardHologramProvider {

    val id: String

    val pluginNames: Set<String>

    fun isAvailable(plugin: MayorPlugin): Boolean

    fun formatLines(lines: List<String>): List<String>

    fun get(name: String): Any?

    fun create(name: String, loc: Location, persistent: Boolean, lines: List<String>): Any?

    fun move(hologram: Any, name: String, loc: Location)

    fun setLines(hologram: Any, lines: List<String>)

    fun remove(name: String)
}
