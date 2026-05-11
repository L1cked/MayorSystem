package mayorSystem.integration.display

import mayorSystem.npc.MayorNpcIdentity
import org.bukkit.Location
import org.bukkit.entity.Entity

interface NpcBackend {
    val id: String
    val available: Boolean

    fun enable()
    fun disable()
    fun restoreFromConfig()
    fun spawnOrMove(location: Location, actorName: String? = null)
    fun remove()
    fun updateMayor(identity: MayorNpcIdentity?)
    fun isOwnedEntity(entity: Entity): Boolean
}

object NoopNpcBackend : NpcBackend {
    override val id: String = "noop"
    override val available: Boolean = false

    override fun enable() = Unit
    override fun disable() = Unit
    override fun restoreFromConfig() = Unit
    override fun spawnOrMove(location: Location, actorName: String?) = Unit
    override fun remove() = Unit
    override fun updateMayor(identity: MayorNpcIdentity?) = Unit
    override fun isOwnedEntity(entity: Entity): Boolean = false
}

interface HologramBackend {
    val id: String
    val available: Boolean

    fun formatLines(lines: List<String>): List<String>
    fun get(name: String): Any?
    fun create(name: String, location: Location, persistent: Boolean, lines: List<String>): Any?
    fun move(hologram: Any, name: String, location: Location)
    fun setLines(hologram: Any, lines: List<String>)
    fun remove(name: String)
}

object NoopHologramBackend : HologramBackend {
    override val id: String = "noop"
    override val available: Boolean = false

    override fun formatLines(lines: List<String>): List<String> = lines
    override fun get(name: String): Any? = null
    override fun create(name: String, location: Location, persistent: Boolean, lines: List<String>): Any? = null
    override fun move(hologram: Any, name: String, location: Location) = Unit
    override fun setLines(hologram: Any, lines: List<String>) = Unit
    override fun remove(name: String) = Unit
}
