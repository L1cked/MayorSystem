package mayorSystem.service

import mayorSystem.MayorPlugin
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory apply wizard state.
 *
 * Why in-memory?
 * - It keeps the elections store clean (we only write once the player confirms + pays).
 * - It avoids half-applied candidates if a player closes the menu or disconnects mid-wizard.
 *
 * If you ever want to make this persistent (for restarts), you can dump/load this map to disk,
 * but for now "confirm" is the only moment we write anything permanent.
 */
class ApplyFlowService(private val plugin: MayorPlugin) : Listener {

    class Session(val termIndex: Int) {
        private val lock = Any()
        private val selectedPerks = LinkedHashSet<String>() // preserve order for nicer summaries

        val chosenPerks: Set<String>
            get() = synchronized(lock) { Collections.unmodifiableSet(LinkedHashSet(selectedPerks)) }

        fun setSelected(perkId: String, selected: Boolean) {
            synchronized(lock) {
                if (selected) selectedPerks.add(perkId) else selectedPerks.remove(perkId)
            }
        }

        fun replaceSelected(perks: Collection<String>) {
            synchronized(lock) {
                selectedPerks.clear()
                selectedPerks.addAll(perks)
            }
        }
    }

    private val sessions = ConcurrentHashMap<UUID, Session>()

    /**
     * Start (or reset) an apply session for this player.
     *
     * If they were already selecting perks for another term, we wipe it, because term windows
     * are term-specific and mixing them would be confusing.
     */
    fun start(player: Player, termIndex: Int): Session {
        val s = Session(termIndex = termIndex)
        sessions[player.uniqueId] = s
        return s
    }

    /**
     * Get an existing session if it's for the same term; otherwise create a fresh one.
     */
    fun getOrStart(player: Player, termIndex: Int): Session {
        return sessions.compute(player.uniqueId) { _, existing ->
            if (existing == null || existing.termIndex != termIndex) Session(termIndex) else existing
        }!!
    }

    fun get(playerId: UUID): Session? = sessions[playerId]

    fun clear(playerId: UUID) {
        sessions.remove(playerId)
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        sessions.remove(e.player.uniqueId)
    }

    /**
     * Convenience helpers used by menus.
     */
    fun setSelected(playerId: UUID, termIndex: Int, perkId: String, selected: Boolean) {
        sessions.computeIfPresent(playerId) { _, session ->
            if (session.termIndex == termIndex) {
                session.setSelected(perkId, selected)
            }
            session
        }
    }

    fun replaceSelected(playerId: UUID, termIndex: Int, perks: Collection<String>) {
        sessions.computeIfPresent(playerId) { _, session ->
            if (session.termIndex == termIndex) {
                session.replaceSelected(perks)
            }
            session
        }
    }
}
