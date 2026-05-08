package mayorSystem.elections.ui

import java.util.UUID
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object AdminForceElectFlow {

    enum class Mode {
        ELECT_NOW,
        SET_FORCED
    }

    data class Session(
        val termIndex: Int,
        val target: UUID,
        val targetName: String,
        val mode: Mode,
        val chosenPerks: Set<String> = emptySet(),
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun start(
        adminId: UUID,
        termIndex: Int,
        target: UUID,
        targetName: String,
        preselected: Set<String>,
        mode: Mode
    ): Session {
        val s = Session(termIndex, target, targetName, mode, immutablePerks(preselected))
        sessions[adminId] = s
        return s
    }

    fun get(adminId: UUID): Session? = sessions[adminId]

    fun setChosenPerks(adminId: UUID, perks: Collection<String>): Session? =
        sessions.computeIfPresent(adminId) { _, session ->
            session.copy(chosenPerks = immutablePerks(perks), updatedAtMs = System.currentTimeMillis())
        }

    fun clear(adminId: UUID) {
        sessions.remove(adminId)
    }

    fun clearStale(maxAgeMs: Long = 15 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        for (entry in sessions.entries) {
            if (now - entry.value.updatedAtMs > maxAgeMs) {
                sessions.remove(entry.key, entry.value)
            }
        }
    }

    private fun immutablePerks(perks: Collection<String>): Set<String> =
        Collections.unmodifiableSet(perks.toCollection(LinkedHashSet()))
}
