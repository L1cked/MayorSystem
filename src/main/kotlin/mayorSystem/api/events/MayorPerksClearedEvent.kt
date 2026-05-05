package mayorSystem.api.events

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

class MayorPerksClearedEvent(
    val term: Int,
    val mayor: UUID,
    val clearedPerkIds: Set<String>
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmField
        val HANDLERS: HandlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
