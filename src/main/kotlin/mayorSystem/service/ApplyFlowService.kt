package mayorSystem.service

import mayorSystem.MayorPlugin
import org.bukkit.entity.Player
import java.util.UUID
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
class ApplyFlowService(private val plugin: MayorPlugin) {

    data class Session(
        val termIndex: Int,
        val chosenPerks: LinkedHashSet<String> = linkedSetOf() // preserve order for nicer summaries
    )

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
        val existing = sessions[player.uniqueId]
        return if (existing == null || existing.termIndex != termIndex) start(player, termIndex) else existing
    }

    fun get(playerId: UUID): Session? = sessions[playerId]

    fun clear(playerId: UUID) {
        sessions.remove(playerId)
    }

    /**
     * Convenience helpers used by menus.
     */
    fun setSelected(playerId: UUID, termIndex: Int, perkId: String, selected: Boolean) {
        val session = sessions[playerId] ?: return
        if (session.termIndex != termIndex) return

        if (selected) session.chosenPerks.add(perkId) else session.chosenPerks.remove(perkId)
    }
}

